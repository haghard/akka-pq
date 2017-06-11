package sample.blog
import cats.Id


object InvariantsDsl {

  trait Check[F[_]] {
    def uniqueName(in: String, state: Set[String]): F[Either[String, String]]
    def existedId(in: Long, state: Set[Long]): F[Either[Long, Long]]
    def both[A,B](l: F[Either[A,A]], r: F[Either[B,B]]): F[Either[String, (A, B)]]
  }

  trait Dsl[T] {
    def apply[F[_]](implicit F: Check[F]): F[T]
  }

  trait PreconditionDsl {
    def uniqueName(in: String, state: Set[String]) = new Dsl[Either[String, String]] {
      override def apply[F[_]](implicit F: Check[F]): F[Either[String, String]] = F.uniqueName(in, state)
    }

    def existedId(in: Long, state: Set[Long]) = new Dsl[Either[Long, Long]] {
      override def apply[F[_]](implicit F: Check[F]): F[Either[Long, Long]] = F.existedId(in, state)
    }

    def both[A, B](l: Dsl[Either[A,A]], r: Dsl[Either[B,B]]): Dsl[Either[String, (A, B)]] = new Dsl[Either[String, (A, B)]] {
      override def apply[F[_]](implicit F: Check[F]): F[Either[String, (A, B)]] = {
        F.both[A,B](l.apply[F], r.apply[F])
      }
    }
  }

  val interp = new Check[cats.Id] {
    override def uniqueName(in: String, state: Set[String]): Id[Either[String, String]] = {
      val r = state.contains(in)
      if(!r) Right(in) else Left(in)
    }
    override def existedId(in: Long, state: Set[Long]): Id[Either[Long, Long]] = {
      val r = state.contains(in)
      if(r) Right(in) else Left(in)
    }

    override def both[A, B](l: Id[Either[A, A]], r: Id[Either[B, B]]): Id[Either[String, (A, B)]] = {
      l.fold({ a => Left("error :" + a) }, { a =>
        r.fold({ b => Left("error : "  + b) }, { b => Right((a,b)) })
      })
    }
  }

  object Preconditions extends PreconditionDsl
  import Preconditions._

  val exp = both(uniqueName("a",  Set("a","b","c")), existedId(1l, Set(2,3,4,5,6,7)))
  exp(interp)
}
