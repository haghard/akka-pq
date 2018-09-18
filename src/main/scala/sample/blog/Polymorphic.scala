package sample.blog;

//http://degoes.net/articles/kill-data
object Polymorphic {

  case class Employee(id: Long, managerId: Long)

  trait EitherLike[E[_, _]] {
    def left[A, B](a: A): E[A, B]
    def right[A, B](b: B): E[A, B]
  }

  /*
  def findManager[E[_, _]: EitherLike](es: List[Employee], e: Employee): E[String, Employee] =
    es.find(_.id == e.managerId).fold(("Could not find manager of " + e).left)(_.right[Employee])
  */

  trait DeconstructEither[F[_, _]] {
    def either[A, B, C](left: A => C, right: B => C)(e: F[A, B]): C
  }

  implicit val Dec = new DeconstructEither[scala.util.Either] {
    override def either[A, B, C](left: (A) => C, right: (B) => C)(e: scala.util.Either[A, B]): C = {
      e.fold(left(_), right(_))
    }
  }

  def leftOrElse[F[_, _], A, B](a: A, e: F[A, B])(implicit E: DeconstructEither[F]): A =
    E.either[A, B, A]({ a: A => a }, Function.const(a))(e)

  trait EitherConstructor[F[_, _], A, B] {
    def left(a: A): F[A, B]
    def right(b: B): F[A, B]
  }
  trait EitherConstruct[F[_, _]] {
    def construct[A, B]: EitherConstructor[F, A, B]
  }

  trait EitherDeconstruct[F[_, _]] {
    def deconstruct[A, B](fa: F[A, B], C: EitherConstructor[F, A, B]): F[A, B]
  }

  implicit val E = new EitherDeconstruct[scala.util.Either]() {
    override def deconstruct[A, B](fa: scala.util.Either[A, B],
      C: EitherConstructor[scala.util.Either, A, B]): scala.util.Either[A, B] = {
      fa.fold({ a: A => C.left(a) }, { b: B => C.right(b) })
    }
  }

  implicit val C = new EitherConstruct[scala.util.Either] {
    override def construct[A, B]: EitherConstructor[scala.util.Either, A, B] =
      new EitherConstructor[scala.util.Either, A, B] {
        def left(a: A): scala.util.Either[A, B] = Left(a)
        def right(b: B): scala.util.Either[A, B] = Right(b)
      }
  }

  //eitherC.construct[String, Int].left("testing")
  //eitherC.construct[String, Int].right(1)


  E.deconstruct(Left("as"), C.construct[String, Int])

  val in: Either[Int, String] = Left(-1)
  leftOrElse[scala.util.Either, Int, String](1, in)

  //  val in: Either[Int, String] = Right("aaaa")
  //  leftOrElse[scala.util.Either, Int, String](-1, in)
  //  1

  /*
  trait Construct0[M0[_], Rep] {
    def make0: M0[Rep]
  }
  trait Construct1[M1[_[_], _], Rep[_]] {
    def make1[A]: M1[Rep, A]
  }
  trait Construct2[M2[_[_, _], _, _], Rep[_, _]] {
    def make2[A, B]: M2[Rep, A, B]
  }
  trait Deconstruct0[M0[_], Rep1] {
    def switch0[Rep2](rep1: Rep1)(m: M0[Rep2]): Rep2
  }
  trait Deconstruct1[M1[_[_], _], Rep1[_]] {
    def switch1[Rep2[_], A](rep1: Rep1[A])(m: M1[Rep2, A]): Rep2[A]
  }
  trait Deconstruct2[M2[_[_, _], _, _], Rep1[_, _]] {
    def switch2[Rep2[_, _], A, B](rep1: Rep1[A, B])(m: M2[Rep2, A, B]): Rep2[A, B]
  }

  trait EitherConstructor[E[_, _], A, B] {
    def left(a: A): E[A, B]
    def right(b: B): E[A, B]
  }
  implicit val eitherConstruct = new Construct2[EitherConstructor, Either] {
    def make2[A, B] = new EitherConstructor[Either, A, B] {
      def left(a: A): Either[A, B] = Left(a)
      def right(b: B): Either[A, B] = Right(b)
    }
  }
  implicit val eitherDeconstruct = new Deconstruct2[EitherConstructor, Either] {
    def switch2[Rep2[_, _], A, B](e: Either[A, B])(m: EitherConstructor[Rep2, A, B]): Either[A, B] =
      e match {
        case Left(v) => m.left(v)
        case Right(v) => m.right(v)
      }
  }


  /*def findManager[E[_, _]](es: List[Employee], e: Employee)(
    implicit C: Construct2[EitherConstructor, E]): E[String, Employee]*/

  def findManager[E[_, _], L[_], P, S](es: L[P], e: P)(
    implicit E: Construct2[EitherConstructor, E],
    implicit L: Deconstruct1[ListConstructor, L],
    implicit P: Deconstruct0[EmployeeConstructor, P],
    implicit S: Construct0[StringConstructor, S]
  ): E[S, P] = ???
  */
}
