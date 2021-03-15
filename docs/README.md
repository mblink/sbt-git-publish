# sbt-git-publish

Adds support for publishing SBT projects to a local git repository and then pushing them to a git remote.

## Install

```scala
resolvers += "bondlink-maven-repo" at "https://raw.githubusercontent.com/mblink/maven-repo/main"
addSbtPlugin("bondlink" % "sbt-git-publish" % "@VERSION@")
```

## Settings

### `gitPublishDir`

The `gitPublishDir` setting is required to configure the path to the local git repository directory for publishing.

```scala
gitPublishDir := file(sys.env("HOME")) / "my-maven-repo"
```

### `gitPublishRemote` (optional)

The `gitPublishRemote` setting controls which configured git remote the plugin pushes releases to. If your git repository only has one configured remote (e.g. `origin`), then the plugin will automatically use it, otherwise you must specify this value.

### `gitPublishBranch` (optional)

The `gitPublishBranch` setting controls which configured git branch the plugin publishes to. By default the plugin tries to guess this value based on the main branch of the configured git remote.

## Usage

To publish your artifacts to the local git repository, simply run

```scala
sbt:sbt-git-publish> publish
```

Then you can release (i.e. push) the changes to the remote git repository via the `gitRelease` task:

```scala
sbt:sbt-git-publish> gitRelease
```
