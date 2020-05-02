package sample.blog

/**
 * Created by haghard on 03/06/2017.
 */
object Events {

  //Type-Level DSLs: Typeclass induction
  //https://www.youtube.com/watch?v=CstiIq4imWM
  //https://t.co/gKsnOpK2kk
  //https://gist.github.com/aaronlevin/d3911ba50d8f5253c85d2c726c63947b

  //Heterogeneous Event Sink

  val SEP = "\t"

  sealed trait Command {
    def id: Long
  }

  case class OrderCreated(id: Long, user: String) extends Command

  case class OrderUpdated(id: Long, user: String, trackId: Long) extends Command

  case class OrderSubmitted(id: Long, user: String, trackId: Long, ts: Long) extends Command

  type CNil = Unit
  type OrderProtocol = (OrderCreated, (OrderUpdated, (OrderSubmitted, CNil)))

  trait ParsedCmd[E] {
    def name: String
  }

  // instances of Named for our events
  implicit val create = new ParsedCmd[OrderCreated] {
    override val name: String = "create"
  }

  implicit val update = new ParsedCmd[OrderUpdated] {
    override val name: String = "update"
  }

  implicit val submit = new ParsedCmd[OrderSubmitted] {
    override val name: String = "submit"
  }

  implicit val baseCaseNamed = new ParsedCmd[CNil] {
    override val name: String = "eof"
  }

  //We leverage implicit resolution to do typeclass induction

  // Named induction step: (E, Tail)
  implicit def inductionStep[E, Tail](implicit n: ParsedCmd[E], tailNames: ParsedCmd[Tail]): ParsedCmd[(E, Tail)] =
    new ParsedCmd[(E, Tail)] {
      override val name = s"${n.name}, ${tailNames.name}"
    }

  def getNamed[E](implicit names: ParsedCmd[E]): String =
    names.name

  // A Typeclass for dynamic-dispatch on events
  trait EventHandler[Events] {
    type Out

    def handleEvent(eventName: String, payload: String): Either[String, Out]
  }

  // HandleEvents: base case
  implicit val baseCaseHandleEvents = new EventHandler[CNil] {
    type Out = Nothing

    def handleEvent(eventName: String, payload: String) = Left(s"Did not find an event $eventName")
  }

  // A typeclass for types that can be parsed from strings.
  trait FromString[E] {
    def apply(line: String): Option[E]
  }

  object FromString {

    // Parser instances for our types.
    implicit val createdFromString = new FromString[OrderCreated] {
      override def apply(s: String): Option[OrderCreated] = s.split(SEP).toList match {
        case id :: user :: Nil ⇒
          safeToLong(id).map(id ⇒ OrderCreated(id, user))
        case _ ⇒ None
      }
    }

    // A small helper
    def safeToLong(s: String): Option[Long] =
      try {
        Some(s.toLong)
      } catch {
        case _: java.lang.NumberFormatException ⇒ None
      }

    implicit val playFromString = new FromString[OrderUpdated] {
      def apply(s: String): Option[OrderUpdated] = s.split(SEP).toList match {
        case id :: user :: trackId :: Nil ⇒
          safeToLong(id).flatMap { id ⇒
            safeToLong(trackId).map { trackId ⇒
              OrderUpdated(id, user, trackId)
            }
          }

        case _ ⇒ None
      }
    }

    implicit val pauseFromString = new FromString[OrderSubmitted] {
      def apply(s: String): Option[OrderSubmitted] = s.split(SEP).toList match {
        case id :: user :: trackId :: ts :: Nil ⇒
          safeToLong(id).flatMap { id ⇒
            safeToLong(trackId).flatMap { trackId ⇒
              safeToLong(ts).map { ts ⇒
                OrderSubmitted(id, user, trackId, ts)
              }
            }
          }

        case _ ⇒ None
      }
    }
  }

  // HandleEvents: induction step (E, Tail)
  implicit def inductionStepHandleEvents[E, Tail](implicit namedEvent: ParsedCmd[E], parser: FromString[E], handler: EventHandler[Tail]): EventHandler[(E, Tail)] =
    new EventHandler[(E, Tail)] {
      type Out = Either[handler.Out, E]

      override def handleEvent(eventName: String, line: String): Either[String, Out] = {
        println("Handle's induction step: " + eventName)
        if (eventName == namedEvent.name) {
          parser.apply(line) match {
            case None    ⇒ Left(s"""Could not decode event "$eventName" with payload "$line"""")
            case Some(e) ⇒ Right(Right(e))
          }
        } else {
          handler.handleEvent(eventName, line) match {
            case Left(e)  ⇒ Left(e)
            case Right(e) ⇒ Right(Left(e))
          }
        }
      }
    }

  def handleEvent[Events](eventTag: String, payload: String)(implicit names: EventHandler[Events]): Either[String, names.Out] =
    names.handleEvent(eventTag, payload)

  println(s"Protocol events ${getNamed[OrderProtocol]}")

  handleEvent[OrderProtocol]("create", "1" + SEP + "haghard")
  //Right(Right(OrderCreated(1,haghard)))

  handleEvent[OrderProtocol]("update", "1" + SEP + "haghard" + SEP + "999")
  //Right(Left(Right(OrderUpdated(1,haghard,999))))

  handleEvent[OrderProtocol]("submit", "1" + SEP + "haghard" + SEP + "999" + SEP + "3457345683563")
  //Right(Left(Left(Right(OrderSubmitted(1,haghard,999,3457345683563)))))

  handleEvent[OrderProtocol]("create2", "1" + SEP + "haghard")
}