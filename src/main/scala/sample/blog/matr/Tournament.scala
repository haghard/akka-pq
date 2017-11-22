package sample.blog.matr

import matryoshka._
import matryoshka.data.{Fix, Mu, Nu}
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
    override def map[A, B](fa: DrawF[A])(f: (A) => B) = fa match {
      case NextGameF(a, b) => NextGameF(f(a), f(b))
      case GameF(a, b) => GameF(a, b)
    }
  }

  def printMatch(): Algebra[Tournament.DrawF, Unit] = {
    case GameF(a, b) =>
      println(s"$a vs $b")
    case NextGameF(_,_) =>
      println("-- node --")
  }

  def offset(level: Int) =
    (0 until level).foldLeft("")((acc, _) => acc + "--")

  def printW(pref: String, level: Int, a: Team, b: Team, w: Team) = {
    println("***********************")
    println(s"${pref}-> $level  $a ")
    println("                   vs")
    println(s"${pref}-> $level  $b")
  }

  type Depth = (Team, Int)
  
  //val evalWinner1: Algebra[Tournament.DrawF, cats.State] = ???

  val evalWinner: Algebra[Tournament.DrawF, Depth] = {
    case GameF(a, b) =>
      val level = 1
      val pref = offset(level)
      if (a.c > b.c) {
        printW(pref, level, a, b, a)
        (a, level)
      } else {
        printW(pref, level, a, b, b)
        (b, level)
      }
    case NextGameF((a, ad), (b, bd)) =>
      val level = ad + 1
      val pref0 = offset(level)
      if (a.c > b.c) {
        printW(pref0, level, a, b, a)
        (a, level)
      } else {
        printW(pref0, level, a, b, b)
        (b, level)
      }
  }


  def probabilityOfWin[T](a: Team, b: Team): Float = {
    val r = if (a.c > b.c) a else b
    println(s"probabilityOfWin: $a vs $b = $r")
    r.c
  }

  //unfold -> anamorphism
  def ana[F[_]: scalaz.Functor, T](a: T)(f: T ⇒ F[T]): Fix[F] = {
    Fix(implicitly[scalaz.Functor[F]].map(f(a))(ana(_)(f)))
    //Fix(ψ(a).map(ana(_)(ψ)))
  }

  //fold to a single value -> catamorphism
  def cata[F[_]: scalaz.Functor, T](fix: Fix[F])(f: F[T] ⇒ T): T = {
    f(implicitly[scalaz.Functor[F]].map(fix.unFix)(cata(_)(f)))
  }

  def cata2[F[_], T](f: F[T] ⇒ T)(fix: Fix[F])(implicit F: scalaz.Functor[F]): T = {
    f(F.map(fix.unFix)(cata(f)))
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

    //f(a).flatMap(_.traverse(anaM(_)(f))).map(Fix(_))
    ???
  }

  //unfold
  def anaTournament[T](participants: List[Team])(implicit T: Corecursive.Aux[T, DrawF]): T = {
    participants match {
      case a :: b :: Nil => GameF[T](a, b).embed
      case xs =>
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


  //https://github.com/sellout/recursion-scheme-talk/blob/master/fix-ing-your-types.org
  //https://japgolly.blogspot.de/2017/11/practical-awesome-recursion-ch-01.html
  //http://kanaka.io/blog/2017/03/05/Nesting-in-the-nest-of-Nesting-Dolls-S01E01.html
}