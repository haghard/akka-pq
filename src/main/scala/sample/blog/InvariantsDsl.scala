package sample.blog

import cats.{ Id, Semigroup, Semigroupal }
import cats.data.Validated._
import cats.syntax.apply._
import cats.data._
import cats.effect.IO
import sample.blog.tl.ParamConcat

import scala.reflect.ClassTag

// import sample.blog.InvariantsDsl
object InvariantsDsl {

  type ErrOrR[T] = ValidatedNel[String, T]

  trait Ops[F[_]] {
    def inSet[T](in: T, state: Set[T], msg: String): F[ErrOrR[T]]

    def notInSet[T](in: T, state: Set[T], msg: String): F[ErrOrR[T]]

    def maybeInSet[T](in: Option[T], state: Set[T], msg: String): F[ErrOrR[T]]

    def inMap[T](in: T, state: Map[T, _], msg: String): F[ErrOrR[T]]

    def maybeInMap[T](in: Option[T], state: Map[T, _], msg: String): F[ErrOrR[T]]

    def and[A, B, AB](l: F[ErrOrR[A]], r: F[ErrOrR[B]]): F[ErrOrR[AB]] //F[R[(A,B)]]

    def or[A, B, AB](l: F[ErrOrR[A]], r: F[ErrOrR[B]]): F[ErrOrR[AB]] //F[R[A Either B]]

    protected def toList[T](v: T): List[Any] = v match {
      case h :: t ⇒ h :: t
      case e      ⇒ List(e)
    }

    private def get[T: ClassTag](list: List[Any]): List[T] =
      list.collect { case a: T ⇒ a }

    protected def tuple[T](v: T): Product = {
      def go[T: ClassTag](v: T, acc: Vector[Any] = Vector()): Vector[Any] = {
        //println(v)
        v match {
          case (a, b) ⇒
            go(a) ++ go(b)
          case (a, b, c) ⇒
            go(a) ++ go(b) ++ go(c)
          case (a, b, c, d) ⇒
            go(a) ++ go(b) ++ go(c) ++ go(d)
          case (a, b, c, d, e) ⇒
            go(a) ++ go(b) ++ go(c) ++ go(d) ++ go(e)
          case (a, b, c, d, e, f) ⇒
            go(a) ++ go(b) ++ go(c) ++ go(d) ++ go(e) ++ go(f)
          case (a, b, c, d, e, f, g) ⇒
            go(a) ++ go(b) ++ go(c) ++ go(d) ++ go(e) ++ go(f) ++ go(g)
          case (a, b, c, d, e, f, g, h) ⇒
            go(a) ++ go(b) ++ go(c) ++ go(d) ++ go(e) ++ go(f) ++ go(g) ++ go(h)
          case Tuple1(a) ⇒ acc :+ a
          case v: T      ⇒ acc :+ v
        }
      }

      def pack(l: Vector[Any]): Product =
        l.size match {
          case 1  ⇒ Tuple1(l(0))
          case 2  ⇒ Tuple2(l(0), l(1))
          case 3  ⇒ Tuple3(l(0), l(1), l(2))
          case 4  ⇒ Tuple4(l(0), l(1), l(2), l(3))
          case 5  ⇒ Tuple5(l(0), l(1), l(2), l(3), l(4))
          case 6  ⇒ Tuple6(l(0), l(1), l(2), l(3), l(4), l(5))
          case 7  ⇒ Tuple7(l(0), l(1), l(2), l(3), l(4), l(5), l(6))
          case 8  ⇒ Tuple8(l(0), l(1), l(2), l(3), l(4), l(5), l(6), l(7))
          case 9  ⇒ Tuple9(l(0), l(1), l(2), l(3), l(4), l(5), l(6), l(7), l(8))
          case 10 ⇒ Tuple10(l(0), l(1), l(2), l(3), l(4), l(5), l(6), l(7), l(8), l(9))
          case 11 ⇒ Tuple11(l(0), l(1), l(2), l(3), l(4), l(5), l(6), l(7), l(8), l(9), l(10))
          case 12 ⇒ Tuple12(l(0), l(1), l(2), l(3), l(4), l(5), l(6), l(7), l(8), l(9), l(10), l(11))
          case 13 ⇒ Tuple13(l(0), l(1), l(2), l(3), l(4), l(5), l(6), l(7), l(8), l(9), l(10), l(11), l(12))
          case 14 ⇒ Tuple14(l(0), l(1), l(2), l(3), l(4), l(5), l(6), l(7), l(8), l(9), l(10), l(11), l(12), l(13))
          case 15 ⇒ Tuple15(l(0), l(1), l(2), l(3), l(4), l(5), l(6), l(7), l(8), l(9), l(10), l(11), l(12), l(13), l(14))
          case 16 ⇒ Tuple16(l(0), l(1), l(2), l(3), l(4), l(5), l(6), l(7), l(8), l(9), l(10), l(11), l(12), l(13), l(14), l(15))
        }

      pack(
        v match {
          case a: Product ⇒ go(a)
          case value      ⇒ Vector(value)
        }
      )
    }
  }

  trait DslElement[T] {
    def apply[F[_]](implicit F: Ops[F]): F[T]
  }

  trait CheckProdDsl {

    def uniqueProd[T](in: T, state: Set[T]): DslElement[ErrOrR[T]] = new DslElement[ErrOrR[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[ErrOrR[T]] = C.notInSet[T](in, state, "uniqueProductName")
    }

    def knownProductName[T](in: T, state: Set[T]): DslElement[ErrOrR[T]] = new DslElement[ErrOrR[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[ErrOrR[T]] = C.notInSet[T](in, state, "knownProductName")
    }

    def knownProductOpt[T](in: Option[T], state: Map[T, _]): DslElement[ErrOrR[T]] = new DslElement[ErrOrR[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[ErrOrR[T]] = C.maybeInMap[T](in, state, "knownProductOpt")
    }
  }

  trait CheckSpecDsl {

    def knownSpec[T](in: T, state: Set[T]): DslElement[ErrOrR[T]] = new DslElement[ErrOrR[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[ErrOrR[T]] = C.inSet[T](in, state, "knownSpecSet")
    }

    def uniqueSpec[T](in: T, state: Set[T]): DslElement[ErrOrR[T]] = new DslElement[ErrOrR[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[ErrOrR[T]] = C.notInSet[T](in, state, "uniqueSpec")
    }

    def knownSpec[T](in: T, state: Map[T, _]): DslElement[ErrOrR[T]] = new DslElement[ErrOrR[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[ErrOrR[T]] = C.inMap[T](in, state, "knownSpecMap")
    }

    def knownSpec[T](in: Option[T], state: Map[T, _]): DslElement[ErrOrR[T]] = new DslElement[ErrOrR[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[ErrOrR[T]] = C.maybeInMap[T](in, state, "knownSpecOptMap")
    }
  }

  trait BasicDsl { self ⇒

    def and[A, B, T](l: DslElement[ErrOrR[A]], r: DslElement[ErrOrR[B]]): DslElement[ErrOrR[T]] = new DslElement[ErrOrR[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[ErrOrR[T]] =
        C.and[A, B, T](l.apply[F], r.apply[F])
    }

    def or[A, B, T](l: DslElement[ErrOrR[A]], r: DslElement[ErrOrR[B]]): DslElement[ErrOrR[T]] = new DslElement[ErrOrR[T]] {
      override def apply[F[_]](implicit C: Ops[F]): F[ErrOrR[T]] =
        C.or[A, B, T](l.apply[F], r.apply[F])
    }

    implicit class DslOpts[A, B](dsl: DslElement[ErrOrR[A]]) {
      def &&[T](that: DslElement[ErrOrR[B]])(implicit ts: ParamConcat.Aux[A, B, T]): DslElement[ErrOrR[T]] =
        self.and(dsl, that)

      def or[T](that: DslElement[ErrOrR[B]])(implicit ts: ParamConcat.Aux[A, B, T]): DslElement[ErrOrR[T]] =
        self.or(dsl, that)
    }

  }

  val ioInterp = new Ops[cats.effect.IO] {

    override def inSet[T](in: T, state: Set[T], name: String): cats.effect.IO[ErrOrR[T]] =
      cats.effect.IO {
        if (state.contains(in)) validNel(in)
        else invalidNel(s"$name failed")
      }

    override def notInSet[T](in: T, state: Set[T], name: String): IO[ErrOrR[T]] =
      cats.effect.IO {
        if (!state.contains(in)) validNel(in)
        else invalidNel(s"$name failed")
      }

    override def inMap[T](in: T, state: Map[T, _], name: String): IO[ErrOrR[T]] =
      IO {
        state.get(in).fold[ErrOrR[T]](invalidNel(s"$name failed")) { _ ⇒ validNel(in) }
      }

    override def maybeInSet[T](in: Option[T], state: Set[T], name: String): IO[ErrOrR[T]] =
      IO {
        in.fold[ErrOrR[T]](validNel(in.asInstanceOf[T])) { el ⇒
          if (state.contains(el)) validNel(el)
          else invalidNel(s"$name failed")
        }
      }

    override def maybeInMap[T](in: Option[T], state: Map[T, _], name: String): IO[ErrOrR[T]] =
      IO {
        in.fold[ErrOrR[T]](validNel(in.asInstanceOf[T])) { el ⇒
          if (state.get(el).isDefined) validNel(el)
          else invalidNel(s"$name failed")
        }
      }

    override def and[A, B, AB](l: IO[ErrOrR[A]], r: IO[ErrOrR[B]]): IO[ErrOrR[AB]] = {
      for {
        a ← l
        b ← r
      } yield {
        a match {
          case Valid(left) ⇒
            b match {
              case Valid(right) ⇒
                //Valid(toList[A](left) ::: toList[B](right))
                Valid(tuple((tuple(left), tuple(right))).asInstanceOf[AB])
              case Invalid(invR) ⇒
                Invalid(invR)
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

    override def or[A, B, AB](l: IO[ErrOrR[A]], r: IO[ErrOrR[B]]): IO[ErrOrR[AB]] = {
      for {
        a ← l
        b ← r
      } yield {
        a match {
          case Valid(left) ⇒
            b match {
              case Valid(right) ⇒
                //Valid(toList[A](left) ::: toList[B](right))
                Valid(tuple((tuple(left), tuple(right))).asInstanceOf[AB])
              case Invalid(invR) ⇒
                //Valid(toList[A](left))
                Valid(tuple(left).asInstanceOf[AB])
            }
          case Invalid(left) ⇒
            b match {
              case Valid(right) ⇒
                //Valid(toList[B](right))
                Valid(tuple(right).asInstanceOf[AB])
              case Invalid(invR) ⇒
                Invalid(left ::: invR)
            }
        }
      }
    }
  }

  val interp = new Ops[cats.Id] {
    override def inSet[T](in: T, state: Set[T], name: String): Id[ErrOrR[T]] =
      if (state.contains(in)) validNel(in) else invalidNel(s"$name failed")

    override def notInSet[T](in: T, state: Set[T], name: String): Id[ErrOrR[T]] =
      if (!state.contains(in)) validNel(in) else invalidNel(s"$name failed")

    override def inMap[T](in: T, state: Map[T, _], name: String): Id[ErrOrR[T]] =
      state.get(in).fold[ErrOrR[T]](invalidNel(s"$name failed")) { _ ⇒ validNel(in) }

    override def maybeInMap[T](in: Option[T], state: Map[T, _], name: String): Id[ErrOrR[T]] =
      in.fold[ErrOrR[T]](validNel(in.asInstanceOf[T]))(inMap(_, state, name))

    override def maybeInSet[T](in: Option[T], state: Set[T], name: String): ErrOrR[T] =
      in.fold[ErrOrR[T]](validNel(in.asInstanceOf[T]))(inSet(_, state, name))

    override def and[A, B, AB](l: Id[ErrOrR[A]], r: Id[ErrOrR[B]]): Id[ErrOrR[AB]] = {
      //Semigroupal.map2(l,r)((a, b) ⇒ (a, b))
      //import cats.implicits._ cats.Traverse[List].sequence(List(a, b))
      val (a, b) = (l, r).mapN { (a, b) ⇒ (a, b) }
      println(s"DEBUG: $a and $b")
      l match {
        case Valid(left) ⇒
          r match {
            case Valid(right) ⇒
              //Valid(toList[A](left) ::: toList[B](right))
              Valid(tuple((tuple(left), tuple(right))).asInstanceOf[AB])
            case Invalid(invR) ⇒
              Invalid(invR)
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

    override def or[A, B, AB](l: Id[ErrOrR[A]], r: Id[ErrOrR[B]]): Id[ErrOrR[AB]] = {
      val (a, b) = (l, r).mapN { (a, b) ⇒ (a, b) }
      println(s"DEBUG: $a or $b")
      import shapeless.Coproduct._
      //type Or = shapeless.:+:[A, shapeless.:+:[B, shapeless.CNil]]
      //shapeless.Coproduct[Or](null.asInstanceOf[A])

      l match {
        case Valid(left) ⇒
          r match {
            case Valid(right) ⇒
              //Valid(toList[A](left) ::: toList[B](right))
              Valid(tuple((tuple(left), tuple(right))).asInstanceOf[AB])
            case Invalid(invR) ⇒
              //Valid(toList[A](left))
              Valid(tuple(left).asInstanceOf[AB])
          }
        case Invalid(left) ⇒
          r match {
            case Valid(right) ⇒
              //Valid(toList[B](right))
              Valid(tuple(right).asInstanceOf[AB])
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

  val expAnd = uniqueProd("a1", Set("a", "b", "c")) && uniqueSpec(1L, Set(2L, 3L, 4L, 5L, 6L, 7L)) or knownSpec(4L, Map(2L -> "a", 3L -> "b"))
  //expAnd(interp)

  knownSpec(Some(8L), Map(2L -> "a", 18L -> "b"))(interp)
  knownProductOpt(Some(1), Map(1 -> "prod_a", 18 -> "prod_b"))(interp)

  val expOr = uniqueProd("e", Set("a", "b", "c")) or knownSpec(8L, Map(2L -> "a", 3L -> "b"))
  /*expOr(interp) match {
    case Invalid(_) ⇒ println("error")
    case Valid(r)   ⇒ println("ok: " + r)
  }*/
  //.fold(_=> println("error"), { case (a,b) => println(s"res: $a:$b") })

  //https://typelevel.org/cats-effect/datatypes/io.html

  import cats.instances._
  import cats.implicits._

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

  findM[scala.util.Try, Int](List(1, 2, 3, 4, 5), 4)

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

    //List[Int] => Validation[List[Int]]
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

    //val expOr = (uniqueProd("b", Set("b", "c")) or knownSpec(Some(21L), Map(21L -> "a", 3L -> "b"))) && uniqueSpec(1, Set(2, 3, 4, 6))

    val expOr = (uniqueProd("b", Set("b", "c")) or knownSpec(Some(21L), Map(21L -> "a", 3L -> "b")))
      .&&(uniqueSpec(1, Set(2, 3, 4, 6)) or uniqueSpec(3, Set(2, 3, 4, 6)))

    println("> " + expOr(interp))

    /*
    sealed trait Op { self ⇒
        def +(other: Op): Op = Both(self, other)
      }
      final case class Both(a: Op, b: Op) extends Op
      final case class Cmd(input: String) extends Op

      val cmds = Cmd("hello") + Cmd("word") + Cmd("!!!")
      run(cmds)

      def run(op: Op): Unit = op match {
        case Both(a, b) ⇒
          run(a)
          run(b)
        case in: Cmd ⇒
          println(s"... $in")
      }
     */

  }

}