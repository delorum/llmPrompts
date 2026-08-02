ThisBuild / scalaVersion := "2.13.16"
ThisBuild / organization := "local.llmprompts"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "llm-prompt-dumper",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson" % "4.1.0",
      "org.scalameta" %% "munit" % "1.1.1" % Test
    ),
    Compile / run / fork := true
  )
