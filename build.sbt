import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.typesafe.sbt.SbtMultiJvm.multiJvmSettings
import scalariform.formatter.preferences._

val akkaVersion = "2.6.19"
val squbsVersion = "0.15.0"

name := "akka-pq"
version := "1.0"
scalaVersion := "2.13.6"

val root = project
  .in(file("."))
  .enablePlugins(MultiJvmPlugin) // use the plugin
  .configs(MultiJvm)
  .settings(multiJvmSettings: _*) // apply the default settings
  .settings(
    resolvers += Resolver.sonatypeRepo("snapshots"),

    //javacOptions in Compile ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),
    Compile / scalacOptions ++= Seq(
      //"-deprecation",
      //"-feature",
      //"-unchecked",
      //"-Xlog-reflective-calls",
      //"-Xlint",
      "-language:higherKinds"
      //"-Ypartial-unification" //for cats and matryoshka
    ),

    // disable parallel tests
    Test / parallelExecution := false,

    logLevel := Level.Info,
    //logLevel := Level.Debug,

    //multiNodeTest - remote run
    //multi-node-test
    //multi-jvm:test - local run
    MultiJvm / multiNodeHosts := Seq("haghard@192.168.77.83", "haghard@192.168.77.69", "haghard@192.168.77.10"),

    run / fork := false,
    javaOptions ++= Seq("-Xmx3G", "-XX:MaxMetaspaceSize=2G", "-XX:+UseG1GC")
  )

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-cluster"                             % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-tools"                       % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding"                    % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-query"                   % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-cassandra"               % "0.103", //"1.0.4"

  "com.lightbend.akka.management" %% "akka-management-cluster-http" % "1.1.3",

  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "ch.qos.logback"    % "logback-classic" % "1.2.11",

  //"com.typesafe.akka" %% "akka-stream"                              % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed"                         % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-typed"                        % akkaVersion,

  "io.monix" %% "monix" % "3.0.0",

  "com.datastax.cassandra" % "cassandra-driver-extras" % "3.10.2",

  //"com.typesafe.akka" %%  "akka-http"      % akkaHttpVersion,
  //"org.hdrhistogram"  %   "HdrHistogram"   % "2.1.9",

  "com.chuusai" %% "shapeless" % "2.3.3",

  "org.typelevel" %% "cats-core"   %  "2.2.0",
  "org.typelevel" %% "cats-effect" %  "2.2.0",

  "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion,
  //"net.cakesolutions" %%  "validated-config"  %  "1.1.2",
  //"com.typesafe.akka" %% "akka-distributed-data" % akkaVersion,

  //"com.scalapenos"    %%  "stamina-json"      % "0.1.3",

  //"com.slamdata" %% "matryoshka-core" % "0.21.3",

  //https://github.com/evolution-gaming/throttler/blob/master/src/test/scala/com/evolutiongaming/util/throttler/RequestThrottlerSpec.scala
  //"com.evolutiongaming" %% "throttler" % "2.0.1",

  "ch.qos.logback" % "logback-classic" % "1.2.11",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
  
  //squbs
  //https://squbs.readthedocs.io/en/latest/presentations/
  "org.squbs" %% "squbs-pattern" % squbsVersion,  //excludeAll("com.typesafe.akka")

  "org.squbs" %% "squbs-ext"     % squbsVersion,  //.excludeAll("com.typesafe.akka")

  //https://docs.scala-lang.org/overviews/parallel-collections/overview.html
  "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4",

  ("com.lihaoyi" % "ammonite" % "2.5.2" % "test").cross(CrossVersion.full),

  "org.iq80.leveldb" % "leveldb" % "0.7" % "test",
  "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8" % "test",
  "org.scalatest" %% "scalatest" % "3.0.8" % "test",
  "commons-io" % "commons-io" % "2.4" % "test")

// ammonite repl
// test:run
Test / sourceGenerators += Def.task {
  val file = (Test / sourceManaged).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue

scalariformPreferences := scalariformPreferences.value
  .setPreference(AlignArguments, true)
  .setPreference(AlignSingleLineCaseStatements, true)
  .setPreference(DoubleIndentConstructorArguments, true)
  .setPreference(DanglingCloseParenthesis, Preserve)
  .setPreference(RewriteArrowSymbols, true)

scalariformAutoformat := true
scalariformWithBaseDirectory := true


addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full)

/*

Here are some examples with variance:
  Tuple2[*, Double]        // equivalent to: type R[A] = Tuple2[A, Double]
  Either[Int, +*]          // equivalent to: type R[+A] = Either[Int, A]
  Function2[-*, Long, +*]  // equivalent to: type R[-A, +B] = Function2[A, Long, B]
  EitherT[*[_], Int, *]    // equivalent to: type R[F[_], B] = EitherT[F, Int, B]

*/

run / fork := false
//true