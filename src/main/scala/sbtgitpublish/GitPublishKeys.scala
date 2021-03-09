package sbtgitpublish

import sbt._

trait GitPublishKeys {
  val gitPublishBranch = settingKey[String]("The git branch to publish to")
  val gitPublishDir = settingKey[File]("The local git repository to publish to")
  val gitPublishRemote = settingKey[String]("The git remote to push releases to")
  val gitPublishRepo = taskKey[Option[Resolver]]("publishTo setting for deploying artifacts to git repository")
  val gitRelease = taskKey[Unit]("Commits and pushes the package to a remote git repository")
}

object GitPublishKeys extends GitPublishKeys
