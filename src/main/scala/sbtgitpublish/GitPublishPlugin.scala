package sbtgitpublish

import org.apache.ivy.core.module.descriptor.{Artifact, DefaultArtifact}
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.plugins.resolver.FileSystemResolver
import sbt._, Keys._
import sbt.internal.librarymanagement.IvyActions
import scala.sys.process._
import scala.util.Try

object GitPublishPlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  object autoImport extends GitPublishKeys {
    implicit class GHPublishResolverSyntax(val resolver: Resolver.type) extends AnyVal {
      def githubRepo(owner: String, repo: String, branch: String = "master"): MavenRepository =
        realm("GitHub", owner, repo, branch) at s"https://raw.githubusercontent.com/$owner/$repo/$branch"

      def gitlabRepo(owner: String, repo: String, branch: String = "master"): MavenRepository =
        realm("GitLab", owner, repo, branch) at s"https://gitlab.com/$owner/$repo/-/raw/$branch"
    }
  }

  import autoImport._

  private def realm(svc: String, owner: String, repo: String, branch: String): String =
    s"$svc repository ($owner/$repo#$branch)"

  private case class GitRemote(name: String) { override def toString: String = name }
  private case class GitBranch(name: String) { override def toString: String = name }

  @volatile private var gitRemoteCache = Map[String, Set[GitRemote]]()
  @volatile private var gitMainBranchCache = Map[(String, GitRemote), GitBranch]()

  private val headBranchPrefix = "HEAD branch: "

  private sealed trait BranchStatus
  private case object BranchUpToDate extends BranchStatus
  private case object BranchBehind extends BranchStatus

  private case class MatchBranchStatus(remote: GitRemote, branch: GitBranch) {
    private val upToDate = ("""^Your branch is up to date with '""" ++ s"$remote/$branch" ++ """'\.$""").r
    private val behind = ("""^Your branch is behind '""" ++ s"$remote/$branch" ++ """' by \d+ commits?, and can be fast-forwarded\.$""").r

    def unapply(s: String): Option[BranchStatus] = s match {
      case upToDate() => Some(BranchUpToDate)
      case behind() => Some(BranchBehind)
      case _ => None
    }
  }

  private def runCmd(log: Logger, cmd: Seq[String]): (List[String], List[String]) = {
    var lines = (List[String](), List[String]())
    def proc(f: Logger => (String => Unit), x: Either[Unit, Unit]): String => Unit =
      Some(_).map(_.trim).filter(_.nonEmpty).foreach { s =>
        f(log)(s)
        lines = x.fold(_ => (lines._1 :+ s, lines._2), _ => (lines._1, lines._2 :+ s))
      }

    cmd.!(ProcessLogger(proc(l => l.info(_: String), Left(())), proc(l => l.error(_: String), Right(())))) match {
      case 0 => lines
      case code => sys.error(s"Command `${cmd.mkString("")}` exited with code $code")
    }
  }

  private def gitCmd(log: Logger, dir: File, args: String*): List[String] =
    runCmd(log, Seq("git", "--git-dir", (dir / ".git").toString, "--work-tree", dir.toString) ++ args)._1

  private def gitRepoMsg(dir: File, rest: String): String =
    s"Git repository $dir $rest"

  private def gitRemotes(log: Logger, dir: File): Set[GitRemote] =
    GitPublishPlugin.synchronized {
      gitRemoteCache.get(dir.toString).getOrElse {
        val remotes = gitCmd(log, dir, "remote").map(GitRemote(_)).toSet
        gitRemoteCache = gitRemoteCache.updated(dir.toString, remotes)
        remotes
      }
    }

  private def gitMainBranch(log: Logger, dir: File, remote: GitRemote): GitBranch =
    GitPublishPlugin.synchronized {
      gitMainBranchCache.get((dir.toString, remote)).getOrElse {
        val branch = gitCmd(log, dir, "remote", "show", remote.name).collectFirst {
          case l if l.startsWith(headBranchPrefix) => GitBranch(l.stripPrefix(headBranchPrefix))
        }.getOrElse(sys.error(gitRepoMsg(dir, s"has no main branch configured for remote ${remote.name}")))
        gitMainBranchCache = gitMainBranchCache.updated((dir.toString, remote), branch)
        branch
      }
    }

  val packagePublishSettings = Seq(
    gitPublishRepo := Def.taskDyn(Def.task {
      val log = streams.value.log
      gitPublishDir.?.value match {
        case Some(f) if (f / ".git").exists => Some(Resolver.file(f.toString,  f))
        case Some(f) =>
          log.warn(gitRepoMsg(gitPublishDir.value, "has no `.git` directory"))
          None
        case None =>
          log.warn("Undefined setting `gitPublishDir`, retaining pre-existing publication settings")
          None
      }
    }).value,
    publishTo := gitPublishRepo.value.orElse(publishTo.value),
    gitRelease := Def.taskDyn(Def.task {
      val log = streams.value.log
      val dir = gitPublishDir.value

      // Get all files expected to be published in local git repo
      val publishConf = publishConfiguration.value
      val publishIvy = ivyModule.value
      val (publishModule, publishCrossVersion) = Some(publishIvy.moduleSettings) match {
        case Some(c: ModuleDescriptorConfiguration) => (c.module, CrossVersion(c.module, c.scalaModuleInfo))
        case _ => sys.error("Module needs ModuleDescriptorConfiguration")
      }
      val publishedFiles: Vector[File] = publishIvy.withModule(log) { case (ivy, md, _) =>
        val repo = ivy.getSettings.getResolver(publishConf.resolverName.getOrElse("")).asInstanceOf[FileSystemResolver]
        val destPattern = repo.getArtifactPatterns.get(0).asInstanceOf[String]
        val getDestFile = {
          val m = classOf[FileSystemResolver].getDeclaredMethod(
            "getDestination",
            classOf[String],
            classOf[Artifact],
            classOf[ModuleRevisionId]
          )
          m.setAccessible(true)
          (a: Artifact) => new File(m.invoke(repo, destPattern, a, md.getModuleRevisionId).asInstanceOf[String])
        }

        IvyActions.mapArtifacts(md, publishCrossVersion, Map(publishConf.artifacts:_*)).flatMap { case (a, f) =>
          getDestFile(a) +: publishConf.checksums.map(c => getDestFile(DefaultArtifact.cloneWithAnotherExt(a, s"${a.getExt}.$c")))
        }
      }

      // Verify all published files exist
      publishedFiles.filterNot(_.exists) match {
        case Vector() => ()
        case missing => sys.error(gitRepoMsg(dir,
          s"does not contain all expected published files. Missing: ${missing.map(s => s"\n  $s").mkString}"))
      }

      // Get the git remote we're publishing to
      val remotes = gitRemotes(log, dir)
      val remote = gitPublishRemote.?.value match {
        case Some(r) => Some(GitRemote(r)).filter(remotes.contains)
          .getOrElse(sys.error(gitRepoMsg(dir, s"has no configured remote named '$r'")))
        case None => gitCmd(log, dir, "remote").toList match {
          case List(s) => GitRemote(s)
          case Nil => sys.error(gitRepoMsg(dir, "has no configured remote"))
          case _ => sys.error(gitRepoMsg(dir, "has more than 1 configured remote, please specify `gitPublishRemote`"))
        }
      }

      // Get the git branch we're publishing to
      val branch = gitPublishBranch.?.value.fold(gitMainBranch(log, dir, remote))(GitBranch(_))

      // Verify the local git repo is clean (no modified or untracked files)
      // Some(gitCmd(log, dir, "status", "--porcelain")).filter(_.isEmpty)
      //   .getOrElse(sys.error(gitRepoMsg(dir, "has uncommitted changes, refusing to release")))

      // Lock to prevent other release processes from modifying local or remote git state
      // while we're committing and pushing this release
      GitPublishPlugin.synchronized {
        // Update the git remote
        gitCmd(log, dir, "remote", "update", remote.name)

        // Verify the local git repo is on the branch we're publishing to
        gitCmd(log, dir, "rev-parse", "--abbrev-ref", "HEAD") match {
          case List(branch.name) => ()
          case _ => gitCmd(log, dir, "checkout", branch.name)
        }

        // Verify the local git repo is up to date with the remote
        // or can be made up to date with a `git merge --ff-only`
        val matchBranchStatus = MatchBranchStatus(remote, branch)
        gitCmd(log, dir, "status", "-uno").collectFirst {
          case matchBranchStatus(BranchUpToDate) => ()
          case matchBranchStatus(BranchBehind) => gitCmd(log, dir, "merge", "--ff-only", s"$remote/$branch")
        }.getOrElse(sys.error(gitRepoMsg(dir, s"is out of sync with $remote/$branch, refusing to release")))

        // Add the published files to the git index
        gitCmd(log, dir, (Seq("add") ++ publishedFiles.map(_.toString)):_*)

        // Commit the published files
        gitCmd(log, dir, "commit", "-m", s"Release $publishModule")

        // Push to the remote
        // gitCmd(log, dir, "push", remote.name, branch.name)
      }
    }).value
  )

  override def projectSettings = packagePublishSettings
}
