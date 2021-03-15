ThisBuild / version := "0.0.5"
ThisBuild / organization := "bondlink"
ThisBuild / homepage := Some(url("https://github.com/mblink/sbt-git-publish"))

lazy val root = project.in(file("."))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-git-publish",
    pluginCrossBuild / sbtVersion := {
      scalaBinaryVersion.value match {
        case "2.12" => "1.2.8"
      }
    },
    publishTo := Some(Resolver.file("file", file("/src/maven-repo")))
  )

lazy val docs = project.in(file("sbt-git-publish-docs"))
  .settings(
    publish := {},
    publishLocal := {}
  )
  .settings(
    moduleName := "sbt-git-publish-docs",
    mdocExtraArguments += "--no-link-hygiene",
    mdocOut := file("."),
    mdocVariables := Map("VERSION" -> version.value)
  )
  .enablePlugins(MdocPlugin)
