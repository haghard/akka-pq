import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

val akkaVersion = "2.5.3"
val akkaHttpVersion = "10.0.9"

val project = Project(
  id = "akka-pq",
  base = file("."),
  settings = Defaults.coreDefaultSettings ++ SbtMultiJvm.multiJvmSettings ++ Seq(
    name := """akka-pq""",
    version := "1.0",
    scalaVersion := "2.12.3",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %%  "akka-cluster" % akkaVersion,
      "com.typesafe.akka" %%  "akka-cluster-tools" % akkaVersion,
      "com.typesafe.akka" %%  "akka-cluster-sharding" % akkaVersion,
      "com.typesafe.akka" %%  "akka-persistence-cassandra" % "0.55",
      "com.typesafe.akka" %%  "akka-stream"    % akkaVersion,
      //"com.typesafe.akka" %%  "akka-http"      % akkaHttpVersion,
      //"org.hdrhistogram"  %   "HdrHistogram"      % "2.1.9",
      "com.chuusai"       %%  "shapeless"         %  "2.3.2",
      "org.typelevel"     %%  "cats"              %  "0.9.0",
      "com.typesafe.akka" %%  "akka-multi-node-testkit" % akkaVersion,
      "net.cakesolutions" %%  "validated-config"  %  "1.1.2",
      "com.typesafe.akka" %% "akka-distributed-data" % akkaVersion,

      "org.iq80.leveldb" % "leveldb" % "0.7" % "test",
      "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8" % "test",
      "org.scalatest" %% "scalatest" % "3.0.1" % "test",
      "commons-io" % "commons-io" % "2.4" % "test"),
    // make sure that MultiJvm test are compiled by the default test compilation
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    // disable parallel tests
    parallelExecution in Test := false,
    // make sure that MultiJvm tests are executed by the default test target, 
    // and combine the results from ordinary test and multi-jvm tests
    executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
      case (testResults, multiNodeResults)  =>
        val overall =
          if (testResults.overall.id < multiNodeResults.overall.id)
            multiNodeResults.overall
          else
            testResults.overall
          Tests.Output(overall,
            testResults.events ++ multiNodeResults.events,
            testResults.summaries ++ multiNodeResults.summaries)
    }
  )
) configs (MultiJvm)

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")

fork in run := true