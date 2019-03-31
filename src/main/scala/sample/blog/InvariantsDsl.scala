package sample.blog

import cats.Id

// import sample.blog.InvariantsDsl
object InvariantsDsl {

  type Out[T] = Either[String, T]

  trait CheckOps[F[_]] {
    def and[A, B](l: F[Out[A]], r: F[Out[B]]): F[Out[(A, B)]]
    def or[A, B](l: F[Out[A]], r: F[Out[B]]): F[Out[A Either B]]
  }

  trait Check[F[_]] extends CheckOps[F] {
    def inSet[T](in: T, state: Set[T]): F[Out[T]]
    def maybeInSet[T](in: Option[T], state: Set[T]): F[Out[T]]
    def inMap[T](in: T, state: Map[T, _]): F[Out[T]]
    def maybeInMap[T](in: Option[T], state: Map[T, _]): F[Out[T]]
  }

  trait DslElement[T] {
    def apply[F[_]](implicit F: Check[F]): F[T]
  }

  trait CheckProdDsl { self =>
    def uniqueProductName[T](in: T, state: Set[T]) = new DslElement[Out[T]] {
      override def apply[F[_]](implicit C: Check[F]): F[Out[T]] = C.inSet[T](in, state)
    }

    def knownProductOpt[F[_], T](in: Option[T], state: Map[T, _]) = new DslElement[Out[T]] {
      override def apply[F[_]](implicit C: Check[F]): F[Out[T]] = C.maybeInMap[T](in, state)
    }
  }

  trait CheckSpecDsl { self =>
    def uniqueSpec[T](in: T, state: Set[T]) = new DslElement[Out[T]] {
      override def apply[F[_]](implicit C: Check[F]): F[Out[T]] = C.inSet[T](in, state)
    }

    def knownSpec[T](in: T, state: Map[T, _]) = new DslElement[Out[T]] {
      override def apply[F[_]](implicit C: Check[F]): F[Out[T]] = C.inMap[T](in, state)
    }

    def knownSpecOpt[F[_], T](in: Option[T], state: Map[T, _]) = new DslElement[Out[T]] {
      override def apply[F[_]](implicit C: Check[F]): F[Out[T]] = C.maybeInMap[T](in, state)
    }
  }

  trait BasicDsl { self =>

    def and[A, B](l: DslElement[Out[A]], r: DslElement[Out[B]]) = new DslElement[Out[(A, B)]] {
      override def apply[F[_]](implicit C: Check[F]): F[Out[(A, B)]] =
        C.and[A, B](l.apply[F], r.apply[F])
    }

    def or[A, B](l: DslElement[Out[A]], r: DslElement[Out[B]]) = new DslElement[Out[A Either B]] {
      override def apply[F[_]](implicit C: Check[F]): F[Out[A Either B]] =
        C.or[A, B](l.apply[F], r.apply[F])
    }

    implicit class PreconditionDslOpts[A, B](dslL: DslElement[Out[A]])   {
      def &&(dslR: DslElement[Out[B]]): DslElement[Out[(A, B)]] = self.and(dslL, dslR)
      def or(dslR: DslElement[Out[B]]): DslElement[Out[A Either B]] = self.or(dslL, dslR)
    }
  }

  trait IdentityCheck extends Check[cats.Id] {
    override def and[A, B](l: Id[Out[A]], r: Id[Out[B]]): Id[Out[(A, B)]] =
      l.fold(Left(_), { la =>
        r.fold(Left(_), (rb => Right((la, rb))))
      })

    override def or[A, B](l: Id[Out[A]], r: Id[Out[B]]): Id[Out[A Either B]] =
      l.fold({ lf =>
        r.fold({ rf => Left(s"both [$lf and $rf] failed")}, (b => Right(Right(b))))
      },(a => Right(Left(a))))
  }

  val interp = new IdentityCheck {
    override def inSet[T](in: T, state: Set[T]): Id[Out[T]] = {
      if (state.contains(in)) Right(in)
      else Left(s"$in doesn't exist in the set")
    }

    override def inMap[T](in: T, state: Map[T, _]): Id[Out[T]] = {
      state.get(in).fold[Out[T]](Left(s"$in doesn't exist in the map")){ _ => Right(in) }
    }

    override def maybeInMap[T](in: Option[T], state: Map[T, _]): Id[Out[T]] = {
      in.fold[Out[T]](Right(in.asInstanceOf[T])) { inMap(_, state) }
    }

    override def maybeInSet[T](in: Option[T], state: Set[T]) = {
      in.fold[Out[T]](Right(in.asInstanceOf[T])) { inSet(_, state) }
    }
  }

  object Preconditions extends BasicDsl with CheckProdDsl with CheckSpecDsl
  import Preconditions._

  val expAnd = ((uniqueProductName("a1", Set("a", "b", "c")) && uniqueSpec(2l, Set(2l, 3l, 4l, 5l, 6l, 7l)))
                 or knownSpec(8l, Map(2l -> "a", 3l -> "b")))
  expAnd(interp)

  knownSpecOpt(Some(8l), Map(2l -> "a", 18l -> "b"))(interp)
  knownProductOpt(Some(1), Map(1 -> "prod_a", 18 -> "prod_b"))(interp)

  val expOr = uniqueProductName("a", Set("a", "b", "c")) or knownSpec(8l, Map(2l -> "a", 3l -> "b"))
  expOr(interp)

  //MonadError
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

  /*def findM[F[_], M[_]: cats.Monad, T](input: M[T], e: T)(implicit E: cats.MonadError[F, Throwable]): F[T] = {
    input.find(_ == e)
      .map(E.pure(_))
      .getOrElse(E.raiseError(new Exception(s"Could not find $e")))
  }*/


  find[scala.util.Try, Int](List(1,2,3,4,5), 4)

  type Or[T] = scala.util.Either[Throwable, T]
  type Validated2[T] = cats.data.Validated[Throwable, T]

  find[Or, Int](List(1,2,3,4,5), 4)
  find[({ type λ[x] = scala.util.Either[Throwable, x] })#λ, Int](List(1,2,3,4,5), 4)
}