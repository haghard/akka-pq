package sample.blog

import scala.language.dynamics

object Lang {

  trait Type
  object Type {
    final case class Unknown() extends Type
    final case class Int() extends Type {
      type ScalaType = BigInt
    }
    final case class Dec() extends Type {
      type ScalaType = BigDecimal
    }
  }
  import Type._
  sealed trait LangType[A <: Type] {
    def typeOf: A
  }
  object LangType {
    def apply[A <: Type](implicit W: LangType[A]): LangType[A] = W

    implicit val UnknownHasType: LangType[Unknown] = new LangType[Unknown] {
      def typeOf = Unknown()
    }
    implicit val IntHasType: LangType[Int] = new LangType[Int] {
      def typeOf = Int()
    }
    implicit val DecHasType: LangType[Dec] = new LangType[Dec] {
      def typeOf = Dec()
    }
  }

  sealed trait NumberT[A]
  object NumberT {
    implicit val IntNumberLike = new NumberT[Type.Int] { }
    implicit val DecNumberLike = new NumberT[Type.Dec] { }
  }

  import Type._

  trait DatasetOps[F[_]]  {
    def empty[A <: Type]: F[A]
    def root: F[Type.Unknown]
    def read(path: String): F[Unknown]
    def map[A <: Type, B <: Type](v: F[A], f: Mapping[A, B]): F[B]
    def distinct[A <: Type](v: F[A]): F[A]
  }

  object DatasetOps {
    def apply[F[_]](implicit F: DatasetOps[F]): DatasetOps[F] = F
  }

  trait Dataset[A <: Type] { self =>
    def apply[F[_]](implicit F: DatasetOps[F]): F[A]

    def typed[B <: Type: LangType]: Dataset[B] = map(_.typed[B])

    def map[B <: Type](f: Mapping[A, A] => Mapping[A, B]): Dataset[B] = new Dataset[B] {
      def apply[F[_]: DatasetOps]: F[B] = DatasetOps[F].map(self.apply[F], f(Mapping.id[A]))
    }

    def distinct: Dataset[A] = new Dataset[A] {
      def apply[F[_]: DatasetOps]: F[A] = DatasetOps[F].distinct(self.apply)
    }
  }

  object Dataset {
    def empty[A <: Type]: Dataset[A] = new Dataset[A] {
      def apply[F[_]](implicit F: DatasetOps[F]): F[A] = F.empty[A]
    }

    def load(path: String) = new Dataset[Unknown] {
      override def apply[F[_]](implicit F: DatasetOps[F]): F[Unknown] = F.read(path)
    }
  }

  trait Mapping[A <: Type, B <: Type] extends Dynamic { self =>
    def apply[F[_]: MappingOps](v: F[A]): F[B]

    def + (that: Mapping[A, B])(implicit N: NumberT[B]): Mapping[A, B] = new Mapping[A, B] {
      def apply[F[_]: MappingOps](v: F[A]): F[B] = MappingOps[F].add(self(v), that(v))
    }

    def - (that: Mapping[A, B])(implicit N: NumberT[B]): Mapping[A, B] = new Mapping[A, B] {
      def apply[F[_]: MappingOps](v: F[A]): F[B] = MappingOps[F].subtract(self(v), that(v))
    }

    def typed[C <: Type: LangType]: Mapping[A, C] = new Mapping[A, C] {
      def apply[F[_]: MappingOps](v: F[A]): F[C] = MappingOps[F].typed(self(v), LangType[C].typeOf)
    }
  }

  object Mapping {
    def id[A <: Type]: Mapping[A, A] = new Mapping[A, A] {
      def apply[F[_]: MappingOps](v: F[A]): F[A] = v
    }
  }

  trait MappingOps[F[_]] {
    def add[A <: Type: NumberT](l: F[A], r: F[A]): F[A]
    def subtract[A <: Type: NumberT](l: F[A], r: F[A]): F[A]
    def typed[A <: Type, B <: Type: LangType](v: F[A], t: B): F[B]
  }

  object MappingOps {
    def apply[F[_]](implicit F: MappingOps[F]): MappingOps[F] = F
  }

  Dataset.load("\\usr\\temp").typed[Type.Int].map { i => i + i }
}

/*
  sealed trait Expr[F[_]] {
    def int(v: Int): F[Int]
    def str(v: String): F[String]
    def add(a: F[Int], b: F[Int]): F[Int]
    def concat(a: F[String], b: F[String]): F[String]
  }

  sealed trait Dsl[T] {
    def apply[F[_]](implicit F: Expr[F]): F[T]
  }

  def int(v: Int) = new Dsl[Int] {
    override def apply[F[_]](implicit F: Expr[F]) = F.int(v)
  }

  def str(v: String) = new Dsl[String] {
    override def apply[F[_]](implicit F: Expr[F]) = ???
  }

  def add(a: Dsl[Int], b: Dsl[Int]) = new Dsl[Int] {
    override def apply[F[_]](implicit F: Expr[F]) = F.add(a.apply[F], b.apply[F])
  }
*/