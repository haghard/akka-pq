package sample.blog

import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.PoisonPill
import akka.actor.ReceiveTimeout
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.PersistentActor

object AuthorListing {

  def props(): Props = Props(new AuthorListing)
    .withDispatcher("cassandra-dispatcher")

  case class PostSummary(author: String, postId: String, title: String)
  case class GetPosts(author: String)
  case class Posts(list: immutable.IndexedSeq[PostSummary])

  val idExtractor: ShardRegion.ExtractEntityId = {
    case s: PostSummary ⇒ (s.author, s)
    case m: GetPosts    ⇒ (m.author, m)
  }

  val numberOfShards = 100
  val shardResolver: ShardRegion.ExtractShardId = msg ⇒ msg match {
    case s: PostSummary ⇒
      (math.abs(s.author.hashCode) % numberOfShards).toString
    case GetPosts(author) ⇒
      (math.abs(author.hashCode) % numberOfShards).toString
    case ShardRegion.StartEntity(id) ⇒
      //???
      // StartEntity is used by remembering entities feature
      (math.abs(id.hashCode) % numberOfShards).toString
  }

  val shardName: String = "AuthorListing"
}

class AuthorListing extends PersistentActor with ActorLogging {
  import AuthorListing._

  override def persistenceId: String = {
    val id = self.path.parent.name + "-" + self.path.name
    log.info("ListingId: {}", id)
    id
  }

  // passivate the entity when no activity
  context.setReceiveTimeout(2.minutes)

  var posts = Vector.empty[PostSummary]

  def receiveCommand = {
    case s: PostSummary ⇒
      persist(s) { evt ⇒
        posts :+= evt
        log.info("Post added to {}'s list: {}", s.author, s.title)
      }
    case GetPosts(_) ⇒
      sender() ! Posts(posts)
    case ReceiveTimeout ⇒ context.parent ! Passivate(stopMessage = PoisonPill)
  }

  override def receiveRecover: Receive = {
    case evt: PostSummary ⇒ posts :+= evt

  }
}