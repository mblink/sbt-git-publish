ThisBuild / version := "0.0.1"
ThisBuild / organization := "bondlink"
ThisBuild / homepage := Some(url("https://github.com/mblink/sbt-github-publish"))

lazy val root = project.in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-github-publish",
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.2.8"
      }
    }
  )
