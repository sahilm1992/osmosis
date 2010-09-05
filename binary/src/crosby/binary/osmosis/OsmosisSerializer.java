package crosby.binary.osmosis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.openstreetmap.osmosis.core.container.v0_6.BoundContainer;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.container.v0_6.EntityProcessor;
import org.openstreetmap.osmosis.core.container.v0_6.NodeContainer;
import org.openstreetmap.osmosis.core.container.v0_6.RelationContainer;
import org.openstreetmap.osmosis.core.container.v0_6.WayContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Bound;
import org.openstreetmap.osmosis.core.domain.v0_6.Entity;
import org.openstreetmap.osmosis.core.domain.v0_6.EntityType;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.OsmUser;
import org.openstreetmap.osmosis.core.domain.v0_6.Relation;
import org.openstreetmap.osmosis.core.domain.v0_6.RelationMember;
import org.openstreetmap.osmosis.core.domain.v0_6.Tag;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;

import crosby.binary.BinarySerializer;
import crosby.binary.Osmformat;
import crosby.binary.Osmformat.DenseInfo;
import crosby.binary.StringTable;
import crosby.binary.Osmformat.Relation.MemberType;
import crosby.binary.file.BlockOutputStream;
import crosby.binary.file.FileBlock;

public class OsmosisSerializer extends BinarySerializer implements Sink {
  protected boolean use_dense = true;
  
  
  public OsmosisSerializer(BlockOutputStream output) {
        super(output);
    }

    public void configUseDense(boolean use_dense) {
      this.use_dense = use_dense;
    }

    abstract class Prim<T extends Entity> {
        ArrayList<T> contents = new ArrayList<T>();

        public void add(T item) {
            contents.add(item);
        }

        public void addStringsToStringtable() {
            StringTable stable = getStringTable();
            for (T i : contents) {
                Collection<Tag> tags = i.getTags();
                for (Tag tag : tags) {
                    stable.incr(tag.getKey());
                    stable.incr(tag.getValue());
                }
                if (omit_metadata == false)
                    stable.incr(i.getUser().getName());
            }
        }

        public void serializeMetadataDense(DenseInfo.Builder b, List<? extends Entity> contents) {
          if (omit_metadata) {
            return;
          }

          long lasttimestamp = 0, lastchangeset = 0;
          int lastuser_sid = 0, lastuid = 0;
          StringTable stable = getStringTable();
          for (Entity e : contents) {

            //if (e.getUser() != OsmUser.NONE) {
            int uid = e.getUser().getId();
            int user_sid = stable.getIndex(e.getUser().getName());
            //}
            int timestamp = (int)(e.getTimestamp().getTime() / date_granularity);
            int version = e.getVersion();
            long changeset = e.getChangesetId();

            b.addVersion(version);
            b.addTimestamp(timestamp-lasttimestamp); lasttimestamp = timestamp;
            b.addChangeset(changeset-lastchangeset); lastchangeset = changeset;
            b.addUid(uid-lastuid); lastuid = uid;
            b.addUserSid(user_sid-lastuser_sid); lastuser_sid = user_sid;
          }
        }
         
       public Osmformat.Info.Builder serializeMetadata(Entity e) {
            StringTable stable = getStringTable();
            Osmformat.Info.Builder b = Osmformat.Info.newBuilder();
            if (omit_metadata) {
                // Nothing
            } else {
                if (e.getUser() != OsmUser.NONE) {
                    b.setUid(e.getUser().getId());
                    b.setUserSid(stable.getIndex(e.getUser().getName()));
                }
                b
                        .setTimestamp((int) (e.getTimestamp().getTime() / date_granularity));
                b.setVersion(e.getVersion());
                b.setChangeset((long) e.getChangesetId());
            }
            return b;
        }
    }

    class NodeGroup extends Prim<Node> implements PrimGroupWriterInterface {

      public void serialize(Osmformat.PrimitiveBlock.Builder parentbuilder) {
          if (use_dense) 
            serializeDense(parentbuilder);
          else
            serializeNonDense(parentbuilder);
      }
        
        /**
         *  Serialize all nodes in the 'dense' format.
         * 
         * @param parentbuilder
         */
        public void serializeDense(Osmformat.PrimitiveBlock.Builder parentbuilder) {
            if (contents.size() == 0)
                return;
            // System.out.format("%d Dense   ",nodes.size());
            Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup
                    .newBuilder();
            StringTable stable = getStringTable();

            long lastlat = 0, lastlon = 0, lastid = 0;
            Osmformat.DenseNodes.Builder bi = Osmformat.DenseNodes.newBuilder();
            boolean doesBlockHaveTags = false;
            // Does anything in this block have tags?
            for (Node i : contents) {
              doesBlockHaveTags = doesBlockHaveTags || (!i.getTags().isEmpty());
            }
            if (omit_metadata) {
              ;// Nothing
            } else {
              Osmformat.DenseInfo.Builder bdi = Osmformat.DenseInfo.newBuilder();
              serializeMetadataDense(bdi,contents);
              bi.setDenseinfo(bdi);
            }
              
              for (Node i : contents) {
                long id = i.getId();
                int lat = mapDegrees(i.getLatitude());
                int lon = mapDegrees(i.getLongitude());
                bi.addId(id - lastid);
                lastid = id;
                bi.addLon(lon - lastlon);
                lastlon = lon;
                bi.addLat(lat - lastlat);
                lastlat = lat;

                // Then we must include tag information.
                if (doesBlockHaveTags) {
                  for (Tag t : i.getTags()) {
                      bi.addKeysVals(stable.getIndex(t.getKey()));
                      bi.addKeysVals(stable.getIndex(t.getValue()));
                  }
                  bi.addKeysVals(0); // Add delimiter.
                }
            }
            builder.setDense(bi);
            parentbuilder.addPrimitivegroup(builder);
        }
        
        public void serializeNonDense(
            Osmformat.PrimitiveBlock.Builder parentbuilder) {
          if (contents.size() == 0)
            return;
          // System.out.format("%d Nodes   ",nodes.size());
          StringTable stable = getStringTable();
          Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup
          .newBuilder();
          for (Node i : contents) {
            long id = i.getId();
            int lat = mapDegrees(i.getLatitude());
            int lon = mapDegrees(i.getLongitude());
            Osmformat.Node.Builder bi = Osmformat.Node.newBuilder();
            bi.setId(id);
            bi.setLon(lon);
            bi.setLat(lat);
            for (Tag t : i.getTags()) {
              bi.addKeys(stable.getIndex(t.getKey()));
              bi.addVals(stable.getIndex(t.getValue()));
            }
            if (omit_metadata) {
              // Nothing.
            } else {
              bi.setInfo(serializeMetadata(i));
            }
            builder.addNodes(bi);
          }
          parentbuilder.addPrimitivegroup(builder);
        }
    
    }

    

    class WayGroup extends Prim<Way> implements PrimGroupWriterInterface {
        public void serialize(Osmformat.PrimitiveBlock.Builder parentbuilder) {
            // System.out.format("%d Ways  ",contents.size());
            StringTable stable = getStringTable();
            Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup
                    .newBuilder();
            for (Way i : contents) {
                Osmformat.Way.Builder bi = Osmformat.Way.newBuilder();
                bi.setId(i.getId());
                long lastid = 0;
                for (WayNode j : i.getWayNodes()) {
                    long id = j.getNodeId();
                    bi.addRefs(id - lastid);
                    lastid = id;
                }
                for (Tag t : i.getTags()) {
                    bi.addKeys(stable.getIndex(t.getKey()));
                    bi.addVals(stable.getIndex(t.getValue()));
                }
                if (omit_metadata) {
                    // Nothing.
                } else {
                    bi.setInfo(serializeMetadata(i));
                }
                builder.addWays(bi);
            }
            parentbuilder.addPrimitivegroup(builder);
        }
    }

    class RelationGroup extends Prim<Relation> implements
            PrimGroupWriterInterface {
        public void addStringsToStringtable() {
            StringTable stable = getStringTable();
            super.addStringsToStringtable();
            for (Relation i : contents)
                for (RelationMember j : i.getMembers())
                    stable.incr(j.getMemberRole());
        }

        public void serialize(Osmformat.PrimitiveBlock.Builder parentbuilder) {
            // System.out.format("%d Relations  ",contents.size());
            StringTable stable = getStringTable();
            Osmformat.PrimitiveGroup.Builder builder = Osmformat.PrimitiveGroup
                    .newBuilder();
            for (Relation i : contents) {
                Osmformat.Relation.Builder bi = Osmformat.Relation.newBuilder();
                bi.setId(i.getId());
                RelationMember arr[] = new RelationMember[i.getMembers().size()];
                i.getMembers().toArray(arr);
                long lastid = 0;
                for (RelationMember j : i.getMembers()) {
                    long id = j.getMemberId();
                    bi.addMemids(id - lastid);
                    lastid = id;
                    if (j.getMemberType() == EntityType.Node)
                        bi.addTypes(MemberType.NODE);
                    else if (j.getMemberType() == EntityType.Way)
                        bi.addTypes(MemberType.WAY);
                    else if (j.getMemberType() == EntityType.Relation)
                        bi.addTypes(MemberType.RELATION);
                    else
                        assert (false); // Software bug: Unknown entity.
                    bi.addRolesSid(stable.getIndex(j.getMemberRole()));
                }

                for (Tag t : i.getTags()) {
                    bi.addKeys(stable.getIndex(t.getKey()));
                    bi.addVals(stable.getIndex(t.getValue()));
                }
                if (omit_metadata) {
                    // Nothing.
                } else {
                    bi.setInfo(serializeMetadata(i));
                }
                builder.addRelations(bi);
            }
            parentbuilder.addPrimitivegroup(builder);
        }
    }

    /* One list for each type */
    NodeGroup bounds;
    WayGroup ways;
    NodeGroup nodes;
    RelationGroup relations;

    private Processor processor = new Processor();

    /**
     * Buffer up events into groups that are all of the same type, or all of the
     * same length, then process each buffer
     */
    public class Processor implements EntityProcessor {
        @Override
        public void process(BoundContainer bound) {
            // Specialcase this. Assume we only ever get one contigious bound
            // request.
            switchTypes();
            processBounds(bound.getEntity());
        }

        public void checkLimit() {
            total_entities++;
            if (++batch_size < batch_limit)
                return;
            switchTypes();
            processBatch();
        }

        @Override
        public void process(NodeContainer node) {
            if (nodes == null) {
                // Need to switch types.
                switchTypes();
                nodes = new NodeGroup();
            }
            nodes.add(node.getEntity());
            checkLimit();
        }

        @Override
        public void process(WayContainer way) {
            if (ways == null) {
                switchTypes();
                ways = new WayGroup();
            }
            ways.add(way.getEntity());
            checkLimit();
        }

        @Override
        public void process(RelationContainer relation) {
            if (relations == null) {
                switchTypes();
                relations = new RelationGroup();
            }
            relations.add(relation.getEntity());
            checkLimit();
        }
    }

    /**
     * At the end of this function, all of the lists of unprocessed 'things'
     * must be null
     */
    private void switchTypes() {
        if (nodes != null) {
            groups.add(nodes);
            nodes = null;
        } else if (ways != null) {
            groups.add(ways);
            ways = null;
        } else if (relations != null) {
            groups.add(relations);
            relations = null;
        } else {
            assert false;
        }
    }

    public void processBounds(Bound entity) {
        Osmformat.HeaderBlock.Builder headerblock = Osmformat.HeaderBlock
                .newBuilder();

        Osmformat.HeaderBBox.Builder bbox = Osmformat.HeaderBBox.newBuilder();
        bbox.setLeft(mapRawDegrees(entity.getLeft()));
        bbox.setBottom(mapRawDegrees(entity.getBottom()));
        bbox.setRight(mapRawDegrees(entity.getRight()));
        bbox.setTop(mapRawDegrees(entity.getTop()));

        headerblock.setBbox(bbox);
        headerblock.addRequiredFeatures("OsmSchema-V0.6");
        if (use_dense)
          headerblock.addRequiredFeatures("DenseNodes");
        Osmformat.HeaderBlock message = headerblock.build();
        try {
            output.write(FileBlock.newInstance("OSMHeader", message
                    .toByteString(), null));
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            throw new Error(e);
        }
        // output.
        // TODO:
    }

    public void process(EntityContainer entityContainer) {
        entityContainer.process(processor);
    }

    @Override
    public void complete() {
        try {
            switchTypes();
            processBatch();
            flush();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    @Override
    public void release() {
        try {
            close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }
}