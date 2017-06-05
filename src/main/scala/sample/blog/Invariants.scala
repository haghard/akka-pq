package sample.blog

import cats.Applicative
import cats.instances.option._

import scala.reflect.ClassTag

//runMain sample.blog.Invariants
object Invariants {

  implicit val setApplicative: Applicative[Set] = new Applicative[Set] {
    override def pure[A](x: A): Set[A] = Set(x)

    override def ap[A, B](ff: Set[(A) => B])(fa: Set[A]): Set[B] =
      for {a <- fa; f <- ff} yield f(a)
  }

  trait Precondition[In, On] {
    def name: String

    def apply(in: In, state: On): Boolean

    def errorMessage(in: In, state: On): String

    def emptyInputMessage = s"Empty input while checking $name"
  }

  sealed trait Invariant[F[_]] {
    def isPreservedOnState[T, State](fa: F[T], on: State, ignoreNull:Boolean = false)
      (implicit P: Precondition[T, State], C: Catamorphism[F]): Either[String, F[Unit]]
  }

  class ExistedId extends Precondition[Long, Set[Long]] {
    override val name = "Precondition: KnownId"

    override def apply(in: Long, on: Set[Long]): Boolean = on.contains(in)

    override def errorMessage(in: Long, on: Set[Long]) = s"$in doesn't exists"
  }

  class UniqueName extends Precondition[String, Set[String]] {
    override val name = "Precondition: UniqueName"

    override def apply(name: String, state: Set[String]): Boolean = !state.contains(name)

    override def errorMessage(name: String, state: Set[String]) = s"$name is not unique"
  }

  class SuitableRoles extends Precondition[Set[Int], Set[Int]] {
    override val name = "Precondition: HasRoles"

    override def apply(ids: Set[Int], state: Set[Int]) = ids.filter(id => !state.contains(id)).isEmpty

    override def errorMessage(ids: Set[Int], state: Set[Int]) = s"User with roles [${ids.mkString(",")}] is not allowed to do this operation"
  }

  implicit val aa = new ExistedId()
  implicit val ab = new UniqueName()
  implicit val ac = new SuitableRoles()

  trait Catamorphism[F[_]] {
    def cata[A, B](opt: F[A])(ifNull: ⇒ Either[String, F[Unit]], ifNotNull: A ⇒ Either[String, F[Unit]]): Either[String, F[Unit]]
  }

  implicit object OptionalCatamorphism extends Catamorphism[Option] {
    override def cata[A, B](opt: Option[A])(
      ifNull: ⇒ Either[String, Option[Unit]],
      ifNotNull: (A) ⇒ Either[String, Option[Unit]]
    ): Either[String, Option[Unit]] = opt.fold(ifNull)(ifNotNull(_))
  }

  implicit object IdCatamorphism extends Catamorphism[cats.Id] {
    override def cata[A, B](opt: cats.Id[A])(
      ifNull: ⇒ Either[String, cats.Id[Unit]],
      ifNotNull: (A) ⇒ Either[String, cats.Id[Unit]]
    ): Either[String, cats.Id[Unit]] = if (opt == null) ifNull else ifNotNull(opt)
  }

  object Invariant {
    def apply[T <: Precondition[_, _] : ClassTag, F[_] : cats.Applicative] = {
      new Invariant[F] {
        val A: cats.Applicative[F] = implicitly[cats.Applicative[F]]
        val success: F[Unit] = A.pure(())
        override def isPreservedOnState[T, State](in: F[T], state: State, ignoreNull: Boolean = false)
          (implicit P: Precondition[T, State], C: Catamorphism[F]): Either[String, F[Unit]] = {
          //println(P.name)
          if(ignoreNull)
            C.cata(in)(Right(success), { input =>
              if (P(input, state)) Right(success) else Left(P.errorMessage(input, state))
            })
          else
            C.cata(in)(Left(P.emptyInputMessage), { input =>
              if (P(input, state)) Right(success) else Left(P.errorMessage(input, state))
            })
        }
      }
    }
  }

  //http://typelevel.org/cats/typeclasses/applicative.html
  def main(args: Array[String]): Unit = {
    val success = Right(1)
    val r =
      for {
        _ <- Invariant[ExistedId, Option]
          .isPreservedOnState(None, Set(103l, 4l, 78l, 32l, 8l, 1l), true)
          .fold(Left(_), { _ => success })

        _ <- Invariant[SuitableRoles, cats.Id]
          .isPreservedOnState(Set(1, 7), Set(1, 3, 4, 5, 6, 7, 8))
          .fold(Left(_), { _ => success })

        out <- Invariant[UniqueName, cats.Id]
          .isPreservedOnState("bq", Set("b", "c", "d", "e"))
          .fold(Left(_), { _ => success })
      } yield out

    println(r)
  }
}