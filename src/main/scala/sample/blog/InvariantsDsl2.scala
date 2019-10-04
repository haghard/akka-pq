package sample.blog

// import sample.blog.InvariantsDsl2
object InvariantsDsl2 {

  type Id[A] = A

  //if validation passes we get None, otherwise - a some with errors
  type R = Option[List[String]]

  trait Ops[F[_]] {

    def inSet[T](in: T, state: Set[T], msg: String): F[R]

    def notInSet[T](in: T, state: Set[T], msg: String): F[R]

    def inMap[T](in: T, state: Map[T, _], msg: String): F[R]

    def and[A, B](a: F[R], b: F[R]): F[R]

    def or[A, B](a: F[R], b: F[R]): F[R]

  }

  trait Dsl[T] {
    def apply[F[_]](implicit F: Ops[F]): F[T]
  }

  trait CheckProdDsl {

    def uniqueProd[T](in: T, state: Set[T]): Dsl[R] = new Dsl[R] {
      override def apply[F[_]](implicit C: Ops[F]): F[R] = C.notInSet[T](in, state, "uniqueProductName")
    }

    def knownProductName[T](in: T, state: Set[T]): Dsl[R] = new Dsl[R] {
      override def apply[F[_]](implicit C: Ops[F]): F[R] = C.notInSet[T](in, state, "knownProductName")
    }
  }

  trait CheckSpecDsl {

    def knownSpec[T](in: T, state: Set[T]): Dsl[R] = new Dsl[R] {
      override def apply[F[_]](implicit C: Ops[F]): F[R] = C.inSet[T](in, state, "knownSpecSet")
    }

    def uniqueSpec[T](in: T, state: Set[T]): Dsl[R] = new Dsl[R] {
      override def apply[F[_]](implicit C: Ops[F]): F[R] = C.notInSet[T](in, state, "uniqueSpec")
    }
  }

  trait BasicDsl {
    self ⇒

    def and[A, B](l: Dsl[R], r: Dsl[R]): Dsl[R] = new Dsl[R] {
      override def apply[F[_]](implicit C: Ops[F]): F[R] =
        C.and[A, B](l.apply[F], r.apply[F])
    }

    def or[A, B](l: Dsl[R], r: Dsl[R]): Dsl[R] = new Dsl[R] {
      override def apply[F[_]](implicit C: Ops[F]): F[R] =
        C.or[A, B](l.apply[F], r.apply[F])
    }

    implicit class DslOpts[A, B](dsl: Dsl[R]) {
      def &&(that: Dsl[R]): Dsl[R] = self.and(dsl, that)

      def or(that: Dsl[R]): Dsl[R] = self.or(dsl, that)
    }

  }

  val interp = new Ops[cats.Id] {

    override def inSet[T](in: T, state: Set[T], name: String): Id[R] =
      if (state.contains(in)) None else Some(List(s"$name failed"))

    override def notInSet[T](in: T, state: Set[T], name: String): Id[R] =
      if (!state.contains(in)) None else Some(List(s"$name failed"))

    override def inMap[T](in: T, state: Map[T, _], name: String): Id[R] =
      state.get(in).fold[R](Some(List(s"$name failed")))(_ ⇒ None)

    override def and[A, B](a: Id[R], b: Id[R]): Id[R] =
      a match {
        case Some(_) ⇒
          b match {
            case Some(errs) ⇒ a.map(_ ::: errs)
            case None       ⇒ a
          }
        case None ⇒
          b match {
            case Some(_) ⇒ b
            case None    ⇒ None
          }
      }

    //cats.Apply[Option].map2(l, r) { (a, b) ⇒ a ::: b }
    override def or[A, B](a: Id[R], b: Id[R]): Id[R] =
      a match {
        case Some(_) ⇒
          b match {
            case Some(errs) ⇒ a.map(_ ::: errs)
            case None       ⇒ None
          }
        case None ⇒
          b match {
            case Some(_) ⇒ None
            case None    ⇒ None
          }
      }
  }

  object Preconditions extends BasicDsl with CheckProdDsl with CheckSpecDsl

  import Preconditions._

  uniqueProd("a1", Set("a", "b", "c")) //  Right("a1")
  uniqueProd("a1", Set("a", "b", "c", "a1")) // Left(a1 doesn't exist in the set)

  uniqueProd("a", Set("a", "b", "c")) && uniqueSpec(1L, Set(2L, 3L, 4L, 5L, 6L, 7L)) or knownSpec(4L, Set(2L, 3L))

  //returns None if in case of success otherwise Some(errors)
  val expAnd = (uniqueSpec(1, Set(2, 3, 4, 6)) && knownSpec(21L, Set(21L, 3L))).or(uniqueProd("b", Set("b", "c")))
  expAnd.apply(interp)
}
