Seq(
  "com.eed3si9n"            % "sbt-assembly"              % "2.3.0",
  "org.scalameta"           % "sbt-scalafmt"              % "2.5.2",
  "ch.epfl.scala"           % "sbt-scalafix"              % "0.13.0",
  "com.eed3si9n"            % "sbt-buildinfo"             % "0.13.1",
  "com.github.sbt"          % "sbt-native-packager"       % "1.10.4",
  "com.github.sbt"          % "sbt-ci-release"            % "1.9.0",
  "org.scoverage"           % "sbt-scoverage"             % "2.2.2",
  "org.typelevel"           % "sbt-fs2-grpc"              % "2.7.21",
).map(addSbtPlugin)
