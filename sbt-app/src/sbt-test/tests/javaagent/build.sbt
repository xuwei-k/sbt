scalaVersion := "3.8.3"

val mockito = "org.mockito" % "mockito-core" % "5.23.0" % Test

libraryDependencies += mockito

libraryDependencies += "org.scalatest" %% "scalatest-funspec" % "3.2.20" % Test

enablePlugins(JavaAgent)

javaAgents += mockito

Test / fork := true

Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Raw
