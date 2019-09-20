package sample.blog

import cats.{ Id, Semigroup, Semigroupal }
import cats.data.NonEmptyList
import cats.data.Validated
import cats.data.Validated._
import cats.syntax.semigroup._
import cats.syntax.validated._
import cats.syntax.apply._
import cats.data._
import cats.effect.IO

//import scalaz.ValidationNel

// import sample.blog.InvariantsDsl
object InvariantsDsl {

  type R[T] = ValidatedNel[String, T]
  //type Errors = NonEmptyList[String]
  //type R[T] = cats.data.Validated[Errors, T]

  trait Ops[F[_]] {
    def inSet[T](in: T, state: Set[T], msg: String): F[R[T]]

    def notInSet[T](in: T, state: Set[T], msg: String): F[R[T]]

    def maybeInSet[T](in: Option[T], state: Set[T], msg: String): F[R[T]]

    def inMap[T](in: T, state: Map[T, _], msg: String): F[R[T]]

    def maybeInMap[T](in: Option[T], state: Map[T, _], msg: String): F[R[T]]

    def and[A, B](l: F[R[A]], r: F[R[B]]): F[R[List[Any]]] //F[R[(A,B)]]

    def or[A, B](l: F[R[A]], r: F[R[B]]): F[R[List[Any]]] //F[R[A Either B]]

    protected def toList[T](v: T): List[Any] = v match {
      case h :: t ⇒ h :: t
      case e      ⇒ List(e)
    }
  }

  trait DslElement[T] {
    def apply[F[_]](implicit F: Ops[F]): F[T]
  }

  trait CheckProdDsl {

    def uniqueProd[T](in: T, state: Set[T]): DslElement[R[T]] = new DslElement[R[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[R[T]] = C.notInSet[T](in, state, "uniqueProductName")
    }

    def knownProductName[T](in: T, state: Set[T]): DslElement[R[T]] = new DslElement[R[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[R[T]] = C.notInSet[T](in, state, "knownProductName")
    }

    def knownProductOpt[T](in: Option[T], state: Map[T, _]): DslElement[R[T]] = new DslElement[R[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[R[T]] = C.maybeInMap[T](in, state, "knownProductOpt")
    }
  }

  trait CheckSpecDsl {

    def knownSpec[T](in: T, state: Set[T]): DslElement[R[T]] = new DslElement[R[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[R[T]] = C.inSet[T](in, state, "knownSpecSet")
    }

    def uniqueSpec[T](in: T, state: Set[T]): DslElement[R[T]] = new DslElement[R[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[R[T]] = C.notInSet[T](in, state, "uniqueSpec")
    }

    def knownSpec[T](in: T, state: Map[T, _]): DslElement[R[T]] = new DslElement[R[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[R[T]] = C.inMap[T](in, state, "knownSpecMap")
    }

    def knownSpec[T](in: Option[T], state: Map[T, _]): DslElement[R[T]] = new DslElement[R[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[R[T]] = C.maybeInMap[T](in, state, "knownSpecOptMap")
    }
  }

  trait BasicDsl { self ⇒

    def and[A, B](l: DslElement[R[A]], r: DslElement[R[B]]): DslElement[R[List[Any]]] = new DslElement[R[List[Any]]] {
      override def apply[F[_]](implicit C: Ops[F]): F[R[List[Any]]] =
        C.and[A, B](l.apply[F], r.apply[F])
    }

    def or[A, B](l: DslElement[R[A]], r: DslElement[R[B]]): DslElement[R[List[Any]]] = new DslElement[R[List[Any]]] {
      override def apply[F[_]](implicit C: Ops[F]): F[R[List[Any]]] =
        C.or[A, B](l.apply[F], r.apply[F])
    }

    implicit class DslOpts[A, B](dslL: DslElement[R[A]]) {
      def &&(dslR: DslElement[R[B]]): DslElement[R[List[Any]]] = self.and(dslL, dslR)

      def or(dslR: DslElement[R[B]]): DslElement[R[List[Any]]] = self.or(dslL, dslR)
    }

  }

  val ioInterp = new Ops[cats.effect.IO] {

    override def inSet[T](in: T, state: Set[T], name: String): cats.effect.IO[R[T]] =
      cats.effect.IO {
        if (state.contains(in)) validNel(in)
        else invalidNel(s"$name failed")
      }

    override def notInSet[T](in: T, state: Set[T], name: String): IO[R[T]] =
      cats.effect.IO {
        if (!state.contains(in)) validNel(in)
        else invalidNel(s"$name failed")
      }

    override def inMap[T](in: T, state: Map[T, _], name: String): IO[R[T]] =
      IO {
        state.get(in).fold[R[T]](invalidNel(s"$name failed")) { _ ⇒ validNel(in) }
      }

    override def maybeInSet[T](in: Option[T], state: Set[T], name: String): IO[R[T]] =
      IO {
        in.fold[R[T]](validNel(in.asInstanceOf[T])) { el ⇒
          if (state.contains(el)) validNel(el)
          else invalidNel(s"$name failed")
        }
      }

    override def maybeInMap[T](in: Option[T], state: Map[T, _], name: String): IO[R[T]] =
      IO {
        in.fold[R[T]](validNel(in.asInstanceOf[T])) { el ⇒
          if (state.get(el).isDefined) validNel(el)
          else invalidNel(s"$name failed")
        }
      }

    override def and[A, B](l: IO[R[A]], r: IO[R[B]]): IO[R[List[Any]]] = {
      for {
        a ← l
        b ← r
      } yield {
        a match {
          case Valid(left) ⇒
            b match {
              case Valid(right) ⇒
                Valid(toList[A](left) ::: toList[B](right))
              case Invalid(invR) ⇒ Invalid(invR)
            }
          case Invalid(left) ⇒
            b match {
              case Valid(_) ⇒
                Invalid(left)
              case Invalid(invR) ⇒
                Invalid(left ::: invR)
            }
        }
      }
    }

    override def or[A, B](l: IO[R[A]], r: IO[R[B]]): IO[R[List[Any]]] = {
      for {
        a ← l
        b ← r
      } yield {
        a match {
          case Valid(left) ⇒
            b match {
              case Valid(right) ⇒
                Valid(toList[A](left) ::: toList[B](right))
              case Invalid(invR) ⇒
                Valid(toList[A](left))
            }
          case Invalid(left) ⇒
            b match {
              case Valid(right) ⇒
                Valid(toList[B](right))
              case Invalid(invR) ⇒
                Invalid(left ::: invR)
            }
        }
      }
    }
  }

  val interp = new Ops[cats.Id] {

    override def inSet[T](in: T, state: Set[T], name: String): Id[R[T]] = {
      if (state.contains(in)) validNel(in)
      else invalidNel(s"$name failed")
    }

    override def notInSet[T](in: T, state: Set[T], name: String): Id[R[T]] = {
      if (!state.contains(in)) validNel(in)
      else invalidNel(s"$name failed")
    }

    override def inMap[T](in: T, state: Map[T, _], name: String): Id[R[T]] = {
      state.get(in).fold[R[T]](invalidNel(s"$name failed")) { _ ⇒ validNel(in) }
    }

    override def maybeInMap[T](in: Option[T], state: Map[T, _], name: String): Id[R[T]] = {
      in.fold[R[T]](validNel(in.asInstanceOf[T]))(inMap(_, state, name))
    }

    override def maybeInSet[T](in: Option[T], state: Set[T], name: String): R[T] = {
      in.fold[R[T]](validNel(in.asInstanceOf[T]))(inSet(_, state, name))
    }

    //HList
    override def and[A, B](l: Id[R[A]], r: Id[R[B]]): /*Id[R[(A, B)]]*/ Id[R[List[Any]]] = {
      //Semigroupal.map2(l,r)((a, b) ⇒ (a, b))
      val (a, b) = (l, r).mapN { (a, b) ⇒ (a, b) }
      println(s"DEBUG: $a and $b")

      /*import cats.implicits._
      cats.Traverse[List].sequence(List(a, b))*/

      l match {
        case Valid(left) ⇒
          r match {
            case Valid(right) ⇒
              Valid(toList[A](left) ::: toList[B](right))
            case Invalid(invR) ⇒ Invalid(invR)
          }
        case Invalid(left) ⇒
          r match {
            case Valid(_) ⇒
              Invalid(left)
            case Invalid(invR) ⇒
              Invalid(left ::: invR)
          }
      }
    }

    override def or[A, B](l: Id[R[A]], r: Id[R[B]]): Id[R[List[Any]]] = {
      val (a, b) = (l, r).mapN { (a, b) ⇒ (a, b) }
      println(s"DEBUG: $a or $b")

      l match {
        case Valid(left) ⇒
          r match {
            case Valid(right) ⇒
              Valid(toList[A](left) ::: toList[B](right))
            case Invalid(invR) ⇒
              Valid(toList[A](left))
          }
        case Invalid(left) ⇒
          r match {
            case Valid(right) ⇒
              Valid(toList[B](right))
            case Invalid(invR) ⇒
              Invalid(left ::: invR)
          }
      }
    }
  }

  object Preconditions extends BasicDsl with CheckProdDsl with CheckSpecDsl

  import Preconditions._

  uniqueProd("a1", Set("a", "b", "c")) //  Right("a1")
  uniqueProd("a1", Set("a", "b", "c", "a1")) // Left(a1 doesn't exist in the set)

  uniqueProd("a1", Set("a", "b", "c")) && uniqueSpec(2L, Set(3L, 4L)) //&& knownSpec(2L, Set(2L, 3L, 4L)) && knownSpec(2L, Set(2L, 3L, 4L))

  //val expAnd = uniqueProd("a1", Set("a", "b", "c")) && uniqueSpec(1L, Set(2L, 3L, 4L, 5L, 6L, 7L)) or knownSpec(4L, Map(2L -> "a", 3L -> "b"))
  //expAnd(interp)

  knownSpec(Some(8L), Map(2L -> "a", 18L -> "b"))(interp)
  knownProductOpt(Some(1), Map(1 -> "prod_a", 18 -> "prod_b"))(interp)

  //val expOr = uniqueProd("a", Set("a", "b", "c")) or knownSpec(8L, Map(2L -> "a", 3L -> "b"))
  //expOr(interp)

  //https://typelevel.org/cats-effect/datatypes/io.html

  import cats.instances._
  import cats.implicits._
  //import cats.implicits._
  import cats.syntax.applicative
  //scala.util.Try or scala.util.Either

  //def find2[F[_], T](es: List[T], e: T)(implicit E: cats.MonadError[F, Throwable]): F[T] = ???

  //val x = implicitly[cats.ApplicativeError[Either[Throwable, ?], Throwable]]]
  //val y = implicitly[cats.ApplicativeError[scala.util.Try, Throwable]]

  //does'n work
  //val z = implicitly[cats.ApplicativeError[cats.data.Validated[Throwable, ?], Throwable]]
  //val z = implicitly[cats.ApplicativeError[scala.Option, Throwable]]

  /*
    For building computations from sequences of values that may fail and then halt the computation
    or to catch those errors in order to resume the computation
   */
  def find[F[_], T](input: List[T], e: T)(implicit E: cats.ApplicativeError[F, Throwable]): F[T] = {
    input.find(_ == e)
      .map(E.pure(_))
      .getOrElse(E.raiseError(new Exception(s"Could not find $e")))
  }

  def doWhile[F[_]: cats.Monad, T: Numeric](input: F[T], value: T): F[T] = {
    implicitly[cats.Monad[F]]
      .iterateWhile(input) { e ⇒
        Thread.sleep(1000)
        val r = implicitly[Numeric[T]].gt(e, value)
        println(s"$e - $value = $r")
        r
      }
  }

  //doWhile(List.range(1, 10), 6)
  //doWhile(Option(1), 3)

  type ME[F[_]] = cats.MonadError[F, Throwable]

  def findM[F[_], T](input: List[T], e: T)(implicit E: cats.MonadError[F, Throwable]): F[T] = {
    input.find(_ == e)
      .map(E.pure(_))
      .getOrElse(E.raiseError(new Exception(s"Could not find $e")))
  }

  //findM[scala.util.Try, Int](List(1, 2, 3, 4, 5), 4)

  find[scala.util.Try, Int](List(1, 2, 3, 4, 5), 4)

  type OrError[T] = scala.util.Either[Throwable, T]

  find[OrError, Int](List(1, 2, 3, 4, 5), 4)
  find[({ type λ[x] = scala.util.Either[Throwable, x] })#λ, Int](List(1, 2, 3, 4, 5), 4)

  /*
  import scalaz._
  import Scalaz._
  type ZErrorOr[T] = scalaz.ValidationNel[Throwable, T]
  type ErrorOr[T] = cats.data.ValidatedNel[Throwable, T]
  type CatsValidated[T] = cats.data.Validated[Throwable, T]

  //scalaz.ValidationNel[Throwable, F[B]]
  def validateZ[F[_]: scalaz.Traverse, A, B](in: F[A])(out: A ⇒ B): ZErrorOr[F[B]] = {
    scalaz.Applicative[ZErrorOr].traverse(in) { a ⇒
      scalaz.Validation.fromTryCatchNonFatal[B](out(a)).toValidationNel
    }
  }
*/

  import cats.data.Validated._

  def main(arg: Array[String]): Unit = {

    /*val a = validateZ(List(1, 2)) { in ⇒
      if (in % 2 == 0) in
      else throw new Exception(s" $in should be even")
    }*/

    //List[Int] => Validatin[List[Int]]
    val b = cats.Traverse[List].traverse(List(1, 2, 3, 4)) { in ⇒
      cats.data.Validated.catchNonFatal {
        if (in % 2 == 0) in
        else throw new Exception(s"$in should be even")
      }.toValidatedNel
    }
    println("b :" + b)

    //List[R] => R[List]
    val c = cats.Traverse[List].sequence(List(validNel(1), validNel("2"), invalidNel("a"), invalidNel("b")))
    println("c :" + c)

    /*
    val exp = uniqueProd("b", Set("a", "c")) && knownSpec(Some(21L), Map(21L -> "a", 3L -> "b")) && uniqueSpec(1, Set(2, 3, 4, 6)) && uniqueSpec(1, Set(2, 3, 4, 6))
    println("> " + exp(interp))
    */

    //without brackets
    or(
      and(
        uniqueSpec(1, Set(2, 3, 4, 6)),
        knownSpec(Some(21L), Map(21L -> "a", 3L -> "b"))
      ),
      uniqueProd("b", Set("b", "c"))
    )

    uniqueProd("b", Set("b", "c")) or knownSpec(Some(21L), Map(21L -> "a", 3L -> "b")) && uniqueSpec(1, Set(2, 3, 4, 6))

    //with brackets
    and(
      or(
        uniqueProd("b", Set("b", "c")),
        knownSpec(Some(21L), Map(21L -> "a", 3L -> "b"))
      ),
      uniqueSpec(1, Set(2, 3, 4, 6))
    )

    val expOr = (uniqueProd("b", Set("b", "c")) or knownSpec(Some(21L), Map(21L -> "a", 3L -> "b"))) && uniqueSpec(1, Set(2, 3, 4, 6))
    println("> " + expOr(interp))
  }

}