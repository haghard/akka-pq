/*

package sample.blog.matr

import matryoshka._
import matryoshka.data.{ Fix, Mu, Nu }
import matryoshka.implicits._

import scalaz.Foldable

//import sample.blog.matr.Tournament
object Tournament {

  type Draw = Fix[DrawF]

  case class Team(name: String, c: Float)

  //Fixed point style algebra
  sealed trait DrawF[A]
  case class NextGameF[A](a: A, b: A) extends DrawF[A]
  case class GameF[A](a: Team, b: Team) extends DrawF[A]

  implicit val tournamentFunctor = new scalaz.Functor[DrawF] {
    override def map[A, B](fa: DrawF[A])(f: (A) ⇒ B) = fa match {
      case NextGameF(a, b) ⇒ NextGameF(f(a), f(b))
      case GameF(a, b)     ⇒ GameF(a, b)
    }
  }

  def printMatch(): Algebra[Tournament.DrawF, Unit] = {
    case GameF(a, b) ⇒
      println(s"$a vs $b")
    case NextGameF(_, _) ⇒
      println("-- node --")
  }

  def offset(level: Int) =
    (0 until level).foldLeft("")((acc, _) ⇒ acc + "--")

  def printW(pref: String, level: Int, a: Team, b: Team, w: Team) = {
    println("***********************")
    println(s"${pref}-> $level  $a ")
    println("                   vs")
    println(s"${pref}-> $level  $b")
  }

  type Depth = (Team, Int)

  //val evalWinner1: Algebra[Tournament.DrawF, cats.State] = ???

  val evalWinner: Algebra[Tournament.DrawF, Depth] = {
    case GameF(a, b) ⇒
      val level = 1
      val pref = offset(level)
      if (a.c > b.c) {
        printW(pref, level, a, b, a)
        (a, level)
      } else {
        printW(pref, level, a, b, b)
        (b, level)
      }
    case NextGameF((a, ad), (b, bd)) ⇒
      val level = ad + 1
      val pref = offset(level)
      if (a.c > b.c) {
        printW(pref, level, a, b, a)
        (a, level)
      } else {
        printW(pref, level, a, b, b)
        (b, level)
      }
  }

  def probabilityOfWin[T](a: Team, b: Team): Float = {
    val r = if (a.c > b.c) a else b
    println(s"probabilityOfWin: $a vs $b = $r")
    r.c
  }

  //unfold -> anamorphism
  //Unfold  ana	(a -> f a) -> a -> Fix f	Unfold
  def ana2[F[_]: scalaz.Functor, T](a: T)(f: T ⇒ F[T]): Fix[F] = {
    //Fix(implicitly[scalaz.Functor[F]].map(f(a))(anaM(_)(f)))
    ???
    //Fix(ψ(a).map(ana(_)(ψ)))
  }

  //fold to a single value -> catamorphism
  //Fold  cata	(f a -> a) -> Fix f -> a
  def cata[F[_]: scalaz.Functor, T](fix: Fix[F])(f: F[T] ⇒ T): T = {
    f(implicitly[scalaz.Functor[F]].map(fix.unFix)(cata(_)(f)))
  }

  def cata2[F[_], T](f: F[T] ⇒ T)(fix: Fix[F])(implicit F: scalaz.Functor[F]): T = {
    f(F.map(fix.unFix)(cata2(f)))
  }

  def anaM[M[_]: scalaz.Monad, F[_]: scalaz.Traverse, A](a: F[A])(f: A ⇒ M[F[A]]): M[Fix[F]] = {
    /*
    Functor[F].map(fa) { g =>
      Foldable[G].foldLeft[A, L Either A](g, Left[L, A](ex)) { (_, b) => Right[L, A](b) }
    }
    */

    /*scalaz.Monad[M].map(f(a)) { g =>
      scalaz.Foldable[F].traverse_(anaM(_)(f))
    }*/

    /*import scalaz.Scalaz._
    import scalaz._

    a.point[]

    scalaz.Monad[M].point(f(a))

    scalaz.Monad[F].point(P.empty[A])
    */

    //M.bind(a) { a0 => M.map(b) { P.plus(a0, _) } }

    //f(a).flatMap(_.traverse(anaM(_)(f))).map(Fix(_))
    ???
  }

  //unfold
  def anaTournament[T](participants: List[Team])(implicit T: Corecursive.Aux[T, DrawF]): T = {
    participants match {
      case a :: b :: Nil ⇒
        GameF[T](a, b).embed
      case xs ⇒
        assert(xs.size % 2 == 0)
        val (l, r) = xs.splitAt(xs.size / 2)
        NextGameF[T](anaTournament(l), anaTournament(r)).embed
    }
  }

  val input = List(Team("okc", 0.62f), Team("hou", 0.65f), Team("csw", 0.89f), Team("mem", 0.74f),
                   Team("tor", 0.48f), Team("was", 0.73f), Team("bos", 0.148f), Team("cav", 0.88f))

  anaTournament[Fix[DrawF]](input).cata(evalWinner)

  //inductive (finite) recursive structures
  anaTournament[Mu[DrawF]](input).cata(evalWinner)

  //coinductive (potentially infinite) recursive structures
  anaTournament[Nu[DrawF]](input).cata(evalWinner)

  sealed trait Tree[+T] {
    def value: T
    def children: List[Tree[T]]
  }

  final case class Node[T](value: T, children: List[Tree[T]] = Nil) extends Tree[T]

  sealed trait TreeF[+T]
  final case class NodeF[T](next: T) extends TreeF[T]
  final case class L(id: Long, starts: String, ends: String) extends TreeF[Nothing]

  sealed trait Tree2[+A, +F]
  final case class Node2[+A, +F](value: A, ch: List[F]) extends Tree2[A, F]
  case object Leaf extends Tree2[Nothing, Nothing]

  type RecursiveTree2[A] = Fix[Tree2[A, ?]]

  val tree =
    Node(
      L(0, "_", "_"),
      List(
        Node(
          L(1, "21/02/2016", "26/02/2016"),
          List(Node(L(12, "21/02/2016", "26/02/2016")), Node(L(13, "21/02/2016", "26/02/2016")))
        ),
        Node(
          L(2, "26/02/2016", "28/02/2016"),
          List(Node(L(21, "21/02/2016", "26/02/2016")), Node(L(22, "21/02/2016", "26/02/2016")))
        )
      )
    )

  implicit val f = new scalaz.Functor[Tree] {
    override def map[A, B](fa: Tree[A])(f: A ⇒ B): Tree[B] = {
      Node(f(fa.value), fa.children.map(map(_)(f)))
    }
  }

  def evalSum[N](implicit N: Numeric[N]): Algebra[Tree2[N, ?], N] = {
    case Node2(v, ch) ⇒ N.plus(v, ch.sum[N])
    case Leaf         ⇒ N.zero
  }

  def evalNodeCount[T]: Algebra[Tree2[T, ?], Int] = {
    case Node2(_, ch) ⇒ 1 + ch.size
    case Leaf         ⇒ 0
  }

  sealed trait TreeF2[+A]
  final case class LeafF2[A](v: L) extends TreeF2[A]
  final case class BranchF2[A](a: A, b: A) extends TreeF2[A]

  implicit val f0 = new scalaz.Functor[TreeF2] {
    override def map[A, B](fa: TreeF2[A])(f: A ⇒ B): TreeF2[B] = {
      fa match {
        case BranchF2(a, b) ⇒ BranchF2(f(a), f(b))
        case LeafF2(v)      ⇒ LeafF2(v)
      }
    }
  }

  /*sealed trait TreeF3[+A]
  final case class LeafF3(v: Int) extends TreeF3[Nothing]
  final case class BranchF3[A](a: TreeF3[A], b: TreeF3[A]) extends TreeF3[A]
  case object BranchF31 extends TreeF3[Nothing]
  final case class Parent[A](children: TreeF3[A]) extends TreeF3[A]

  BranchF3(
    BranchF3(
          Parent(
            BranchF3(
              BranchF3(
                BranchF3(LeafF3(1), LeafF3(2)),
                BranchF3(LeafF3(3), LeafF3(4))
              ),
              BranchF3(
                BranchF3(LeafF3(5), LeafF3(6)),
                BranchF3(LeafF3(8), LeafF3(7))
              )
            )
          ), BranchF31),
    BranchF3(
      Parent(
        BranchF3(
          BranchF3(
            BranchF3(LeafF3(1), LeafF3(2)),
            BranchF3(LeafF3(3), LeafF3(4))
          ),
          BranchF3(
            BranchF3(LeafF3(5), LeafF3(6)),
            BranchF3(LeafF3(8), LeafF3(7))
          )
        )
      ),

      Parent(
        BranchF3(
          BranchF3(
            BranchF3(LeafF3(9), LeafF3(10)),
            BranchF3(LeafF3(11), LeafF3(12))
          ),
          BranchF3(
            BranchF3(LeafF3(13), LeafF3(14)),
            BranchF3(LeafF3(16), LeafF3(15))
          )
        )
      )
    )
  )
*/

  sealed trait Tree3[+A]
  final case class Child2[A](a: L, b: L) extends Tree3[A]
  final case class Child3[A](a: L, b: L, c: L) extends Tree3[A]
  final case class Child4[A](a: L, b: L, c: L, d: L) extends Tree3[A]

  final case class Child1[A](l: L) extends Tree3[A]

  final case class LeafOfTwo[A](a: A, b: A) extends Tree3[A]

  implicit val functor = new scalaz.Functor[Tree3] {
    override def map[A, B](fa: Tree3[A])(f: A ⇒ B): Tree3[B] = fa match {
      case Child1(l)          ⇒ Child1(l)
      case Child2(a, b)       ⇒ Child2(a, b)
      case Child3(a, b, c)    ⇒ Child3(a, b, c)
      case Child4(a, b, c, d) ⇒ Child4(a, b, c, d)
      case LeafOfTwo(a, b)    ⇒ LeafOfTwo(f(a), f(b))
    }
  }

  def evalCount: Algebra[TreeF2, Long] = {
    case LeafF2(a)      ⇒ a.id
    case BranchF2(a, b) ⇒ a + b
  }

  /*def evalCount2: Algebra[Tree3, Tree3] = {
    case Child1(a)        => 1
    case Child2(a,b)      => 2
    case Child3(a,b,c)    => 3
    case Child4(a,b,c,d)  => 4
    case LeafOfTwo(_,_)   =>
  }*/

  def print: Algebra[Tree3, Unit] = {
    case Child1(l) ⇒
      println(l.id)
    case LeafOfTwo(_, _) ⇒
      println("----")
  }

  def anaM[T](in: List[L])(implicit T: Corecursive.Aux[T, Tree3]): T = {
    in match {
      case a :: Nil ⇒
        Child1[T](a).embed
      case a :: b :: Nil ⇒
        LeafOfTwo[T](Child1[T](a).embed, Child1[T](b).embed).embed
      case xs ⇒
        assert(xs.size % 2 == 0)
        val (l, r) = xs.splitAt(xs.size / 2)
        LeafOfTwo[T](anaM(l), anaM(r)).embed
    }
  }

  /*def ana2M[T](in: List[L])(implicit T: Corecursive.Aux[T, TreeF2]): T = {
    in match {
      case a :: Nil =>
        LeafF2[T](a).embed
      case a :: b :: Nil =>
        BranchF2[T](LeafF2[T](a).embed, LeafF2[T](b).embed).embed
      case xs =>
        assert(xs.size % 2 == 0)
        val (l, r) = xs.splitAt(xs.size / 2)
        BranchF2[T](anaM(l), anaM(r)).embed
    }
  }*/

  val in =
    List(
      L(1, "21/02/2016", "26/02/2016"), L(12, "21/02/2016", "26/02/2016"), L(13, "21/02/2016", "26/02/2016"),
      L(2, "26/02/2016", "28/02/2016"), L(21, "21/02/2016", "26/02/2016"), L(22, "21/02/2016", "26/02/2016"),
      L(23, "21/02/2016", "26/02/2016"), L(24, "21/02/2016", "26/02/2016"))

  anaM[Fix[Tree3]](in).cata(print)
  anaM[Nu[Tree3]](in).cata(print)
  anaM[Mu[Tree3]](in).cata(print)

  //tree.cata(eval)

  //ana2[Tree3, Int] { t => }

  /*ana2[Tree](tree) { t: Tree[Lit] =>
    Node[Lit](t.value.id * 2, t.children)
  }*/

  //https://github.com/sellout/recursion-scheme-talk/blob/master/fix-ing-your-types.org
  //https://japgolly.blogspot.de/2017/11/practical-awesome-recursion-ch-01.html
  //http://kanaka.io/blog/2017/03/05/Nesting-in-the-nest-of-Nesting-Dolls-S01E01.html
  //https://github.com/japgolly/microlibs-scala/tree/master/recursion
}*/
