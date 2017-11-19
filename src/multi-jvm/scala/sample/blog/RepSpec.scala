package sample.blog

import akka.cluster.Cluster
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import akka.testkit.ImplicitSender
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils

object RepSpec extends MultiNodeConfig {
  val controller = role("controller")
  val node1 = role("node1")
  val node2 = role("node2")

  commonConfig(ConfigFactory.parseString("""
    |cassandra-dispatcher {
    |  type = Dispatcher
    |  executor = "fork-join-executor"
    |  fork-join-executor {
    |    parallelism-min = 2
    |    parallelism-max = 8
    |  }
    |}
    |  akka.cluster.metrics.enabled=off
    |  akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    |  akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    |  akka.persistence.journal.leveldb-shared.store {
    |    native = off
    |    dir = "target/test-shared-journal"
    |  }
    |  akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    |  akka.persistence.snapshot-store.local.dir = "target/test-snapshots"
    """.stripMargin))
}

object RepSpecMultiJvmNode1 extends RepSpec
object RepSpecMultiJvmNode2 extends RepSpec
object RepSpecMultiJvmNode3 extends RepSpec

class RepSpec extends MultiNodeSpec(RepSpec) with STMultiNodeSpec with ImplicitSender {
  import RepSpec._

  override def initialParticipants = roles.size

  override protected def atStartup() {
    runOn(controller) {
      //storageLocations.foreach(dir => FileUtils.deleteDirectory(dir))
    }
  }

  override protected def afterTermination() {
    runOn(controller) {
      //storageLocations.foreach(dir => FileUtils.deleteDirectory(dir))
    }
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      //startSharding()
    }
    enterBarrier(from.name + "-joined")
  }
  
}
