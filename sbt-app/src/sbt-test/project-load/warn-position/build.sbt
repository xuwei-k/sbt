version := "1"

version := "2"

version := "3"

version := "4"

version := "5"

InputKey[Int]("a") := {
  Stream("a").size
}

dependencyOverrides ++= Seq(
)

TaskKey[Int]("b") := Def.uncached {
  Stream("b").size
}
