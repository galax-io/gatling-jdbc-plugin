import Dependencies.*

// sbt-git's default JGit reader throws NoWorkTreeException in linked git worktrees
// (where `.git` is a file, not a directory), which breaks project loading there.
// Shell out to the git CLI for read-only git ops so GitVersioning loads from
// worktrees too. (sbt-git helper; sets ThisBuild / useConsoleForROGit := true.)
useReadableConsoleGit

lazy val root = (project in file("."))
  .enablePlugins(GitVersioning, GatlingPlugin)
  .settings(
    name                        := "gatling-jdbc-plugin",
    scalaVersion                := "2.13.18",
    // Do not publish artifacts for Gatling-specific configurations (simulations/tests)
    Gatling / publishArtifact   := false,
    GatlingIt / publishArtifact := false,
    libraryDependencies ++= gatling ++ gatlingCore,
    libraryDependencies ++= Seq(hikari, h2jdbc, scalatest, testcontainersPg, postgresJdbc),
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",            // Option and arguments on same line
      "-Xfatal-warnings", // New lines for each options
      "-deprecation",
      "-feature",
      "-unchecked",
      "-language:implicitConversions",
      "-language:higherKinds",
      "-language:existentials",
      "-language:postfixOps",
    ),
  )
