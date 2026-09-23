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
    // Binary-compatibility check against the latest published release.
    mimaPreviousArtifacts       := Set(organization.value %% name.value % "1.5.0"),
    // Coverage floor, set just under measured (stmt=84.45% branch=86.19%, 2026-09-22).
    // Only ever moves up.
    coverageMinimumStmtTotal    := 80,
    coverageMinimumBranchTotal  := 82,
    coverageFailOnMinimum       := true,
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
