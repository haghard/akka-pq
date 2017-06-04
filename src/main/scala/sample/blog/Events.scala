package sample.blog

/**
 * Created by haghard on 03/06/2017.
 */
object Events {

  //Typeclass Induction
  //https://t.co/gKsnOpK2kk
  //https://gist.github.com/aaronlevin/d3911ba50d8f5253c85d2c726c63947b

  sealed trait Command {
    def id: Long
  }

  case class OrderCreated(id: Long, user: String) extends Command

  case class OrderUpdated(id: Long, user: String, trackId: Long) extends Command

  case class OrderSubmitted(id: Long, user: String, trackId: Long, ts: Long) extends Command

  type CNil = Unit
  type OrderProtocol = (OrderCreated, (OrderUpdated, (OrderSubmitted, CNil)))


  trait ParsedCmd[E] {
    val name: String
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
    override val name: String = ""
  }


  // Named induction step: (E, Tail)
  implicit def inductionStepNamed[E, Tail](implicit n: ParsedCmd[E], tailNames: ParsedCmd[Tail]) =
    new ParsedCmd[(E, Tail)] {
      override val name = s"${n.name}, ${tailNames.name}"
    }

  // helper
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

    def handleEvent(eventName: String, payload: String) = Left(s"Did not find event $eventName")
  }

  // A typeclass for types that can be parsed from strings.
  trait Parser[E] {
    def fromString(line: String): Option[E]
  }

  // Parser instances for our types.
  implicit val createdFromString = new Parser[OrderCreated] {
    override def fromString(s: String) = s.split('\t').toList match {
      case id :: user :: Nil =>
        safeToLong(id).map(id => OrderCreated(id, user))
      case _ => None
    }
  }

  // A small helper
  def safeToLong(s: String): Option[Long] =
    try {
      Some(s.toLong)
    } catch {
      case _: java.lang.NumberFormatException => None
    }


  implicit val playFromString = new Parser[OrderUpdated] {
    def fromString(s: String) = s.split('\t').toList match {
      case id :: user :: trackId :: Nil =>
        safeToLong(id).flatMap { id =>
          safeToLong(trackId).map { trackId =>
            OrderUpdated(id, user, trackId)
          }
        }

      case _ => None
    }
  }

  implicit val pauseFromString = new Parser[OrderSubmitted] {
    def fromString(s: String) = s.split('\t').toList match {
      case id :: user :: trackId :: ts :: Nil =>
        safeToLong(id).flatMap { id =>
          safeToLong(trackId).flatMap { trackId =>
            safeToLong(ts).map { ts =>
              OrderSubmitted(id, user, trackId, ts)
            }
          }
        }

      case _ => None
    }
  }


  // HandleEvents: induction step (E, Tail)
  implicit def inductionStepHandleEvents[E, Tail](implicit namedEvent: ParsedCmd[E], parser: Parser[E], handler: EventHandler[Tail]) =
    new EventHandler[(E, Tail)] {
      type Out = Either[handler.Out, E]

      override def handleEvent(eventName: String, line: String): Either[String, Out] = {
        if (eventName == namedEvent.name) {
          parser.fromString(line) match {
            case None => Left(s"""Could not decode event "$eventName" with payload "$line"""")
            case Some(e) => Right(Right(e))
          }
        } else {
          handler.handleEvent(eventName, line) match {
            case Left(e) => Left(e)
            case Right(e) => Right(Left(e))
          }
        }
      }
    }

  def handleEvent[Events](eventName: String, payload: String)(implicit names: EventHandler[Events]): Either[String, names.Out] =
    names.handleEvent(eventName, payload)


  println(s"Protocol events ${getNamed[OrderProtocol]}")

  handleEvent[OrderProtocol]("create", "1\thaghard")
  //Right(Right(OrderCreated(1,haghard)))

  handleEvent[OrderProtocol]("update", "1\thaghard\t999")
  //Right(Left(Right(OrderUpdated(1,haghard,999))))

  handleEvent[OrderProtocol]("submit", "1\thaghard\t999\t3457345683563")
  //Right(Left(Left(Right(OrderSubmitted(1,haghard,999,3457345683563)))))

  val r = handleEvent[OrderProtocol]("create2", "1\thaghard")

  r.fold({ a: String => 9l }, { a =>
    a
  })
  
  //r.fold(9l, { a => a.fold(8l, { b => b.id }) }))

}