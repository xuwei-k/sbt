// https://github.com/sbt/sbt/issues/7723
trait A {
  def x: Stream[Int]
  def x2: Stream[Int]
}
