/*
  You can use Cluster Sharding and DData with roles. So, let's say that you go with 10 roles, 10,000 entities in each role.
  You would then start Replicators on the nodes with corresponding roles.
  You would also start Sharding on the nodes with corresponding roles.
  On a node that doesn't have the role you would start a sharding proxy for such role.

  When you want to send a message to an entity you first need to decide which role to use for that message.
  Can be simple hashCode modulo algorithm.
  Then you delegate the message to the corresponding Sharding region or proxy actor.

  You have defined the Props for the entities and there you pass in the Replicator corresponding to the role that the entity
  belongs to, i.e. the entity takes the right Replicator ActorRef as constructor parameter.

  If you don't need the strict guarantees of "only one entity" that Cluster Sharding provides, and prefer better availability in
  case of network partitions, you could use a consistent hashing group router instead of Cluster Sharding.
  You would have one router per role, and decide router similar as above.
  Then the entities (routees of the router) would have to subscribe to changes
  from DData to get notified of when a peer entity has changed something, since you can have more than one alive at the same time.
  */


  /*
  1. How are Replicators tied to node roles?

  Replicator.props takes ReplicatorSettings, which contains a role property.

  Can I start more than 1 Replicator on a node?
  Yes, just start it as an ordinary actor. Make sure that you use the same actor name on other nodes that it should interact with.

  If so, can I start only as many Replicators as the roles this node has?

  Yes, that was my idea

  2. If a node has K roles, does it mean that its K replicators gossip independently of each other?

  Yes

  3. In the last scenario --- one consistent hashing group router per role --- why do routees subscribe to changes from DData? Shouldn't DData be replicated across all nodes with role_i? If so, they can simply read the data if they are on the node with the right role.

  Yes they can read instead, but then you would need to know when to read. Perhaps you do that for each request, that would also work.
   */
  object DbShardEntity {
    def props(replicator: ActorRef) = Props(new DbShardEntity(replicator))
  }
  class DbShardEntity(replicator: ActorRef) extends Actor {
    override def receive: Receive = ???
  }

  //val replicatorA = system.actorOf(Replicator.props(settings), name)
  //val replicatorB = system.actorOf(Replicator.props(settings), name)
  //val replicatorC = system.actorOf(Replicator.props(settings), name)
  //ClusterSharding(system).startProxy()
  val DbShards = ClusterSharding(node1).start(
    typeName = "db",
    entityProps = DbShardEntity.props(null),
    settings = ClusterShardingSettings(node1),
    extractEntityId = AuthorListing.idExtractor,
    extractShardId = AuthorListing.shardResolver)



  val shardAGroup = node1.actorOf(
    ClusterRouterGroup(
      ConsistentHashingGroup(List("/user/shardA/replicaA1", "user/shardA/replicaA2", "/user/shardA/replicaA3")),
        ClusterRouterGroupSettings.apply(
          totalInstances = 3,
          routeesPaths = immutable.Seq[String]("/user/shardA"),
          allowLocalRoutees = false,
          useRole = Some("shardA"))
    ).props(), "shardA-replicas")

  val shardAPool =
    ClusterRouterPool(
      ConsistentHashingPool(nrOfInstances = 3, virtualNodesFactor = 10
        /*hashMapping = hashMapping*//*, supervisorStrategy = clusteredRouterSupervisorStrategy*/
      ),
      ClusterRouterPoolSettings(totalInstances = 10, maxInstancesPerNode = 1,
        allowLocalRoutees = true, useRole = Some("shardA"))
    )

  /*
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) ⇒ (id.toString, payload)
    case msg @ Get(id)               ⇒ (id.toString, msg)
  }

  val numberOfShards = 100

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _) ⇒ (id % numberOfShards).toString
    case Get(id)               ⇒ (id % numberOfShards).toString
  }
  */


  //https://groups.google.com/forum/#!topic/akka-user/MO-4XhwhAN0
    val name = s"replicator-$shardId"

    val config = ConfigFactory.parseString(
      s"""
        | name = $name
        | role = replicaA
        | gossip-interval = 1 s
        | use-dispatcher = ""
        | notify-subscribers-interval = 500 ms
        | max-delta-elements = 1000
        | pruning-interval = 120 s
        | max-pruning-dissemination = 300 s
        | pruning-marker-time-to-live = 6 h
        | serializer-cache-time-to-live = 10s
        | delta-crdt {
        |   enabled = on
        |   max-delta-size = 1000
        | }
        |
        | durable {
        |  keys = []
        |  pruning-marker-time-to-live = 10 d
        |  store-actor-class = akka.cluster.ddata.LmdbDurableStore
        |  use-dispatcher = akka.cluster.distributed-data.durable.pinned-store
        |  pinned-store {
        |    executor = thread-pool-executor
        |    type = PinnedDispatcher
        |  }
        |
        |  lmdb {
        |    dir = "ddata"
        |    map-size = 100 MiB
        |    write-behind-interval = off
        |  }
        | }
      """.stripMargin)

    val settings = ReplicatorSettings(config)
    val replicator = s.actorOf(Replicator.props(settings), name)