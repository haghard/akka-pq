package sample.blog

import akka.actor.Status.Failure
import akka.actor.SupervisorStrategy.Decider
import akka.actor.{Actor, ActorInitializationException, ActorLogging, ActorRef, ActorSystem, AllForOneStrategy, Props, SupervisorStrategy, Terminated}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.io.StdIn


class Observer(p: ActorRef) extends Actor with ActorLogging {
  context.watch(p)

  override def receive: Receive = {
    case Terminated(`p`) =>
      println(s"Terminated $p")
      context.stop(self)
  }
}

class Parent extends Actor with ActorLogging {
  override def preStart(): Unit = {
    println("Parent preStart " + self.path)
  }

  override val supervisorStrategy: SupervisorStrategy = AllForOneStrategy()(
    {
      case ActorInitializationException(actor, message, cause) =>
        println(s"Parent caught a child Initialization failure : $message")
        context.stop(self)
        akka.actor.SupervisorStrategy.Stop
      case ex: Throwable =>
        println(s"Parent caught a child failure ${ex.getClass.getName} :" + ex.getMessage)
        context.stop(self)
        akka.actor.SupervisorStrategy.Stop
    }: Decider
  )

  context.actorOf(Props(new Child), "child-a")

  override def receive: Receive = {
    case _ =>
  }
}

class Child extends Actor with ActorLogging {
  implicit val _ = context.dispatcher

  def async() = Future {
    Thread.sleep(8000)
    throw new Exception("Boom !!!!!!!!!!!")
    1
  }

  //val r = Await.result(async, Duration.Inf)
  import akka.pattern.pipe
  async().pipeTo(self)

  override def preStart(): Unit = {
    println("Child preStart " + self.path + " parent:" + context.parent.path)
    import scala.concurrent.duration._
    context.system.scheduler.schedule(0.seconds, 3.seconds)(self ! 'Work)
  }

  override def receive: Receive = {
    case Failure(ex) =>
      println(s"Failure in child:" + ex.getMessage)
      throw ex
    case 'Work =>
      println("work")
  }
}

object SupervisionApp {

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load
    implicit val system = ActorSystem("sys", config)

    val p = system.actorOf(Props(new Parent), "parent")
    val o = system.actorOf(Props(new Observer(p)), "obs")

    StdIn.readLine()
  }
}