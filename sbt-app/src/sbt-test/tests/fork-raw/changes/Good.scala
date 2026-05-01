package testpkg

import munit.*

class ATest extends FunSuite:
  test("sum"):
    assert(1 + 1 == 2)

  test("classpath"):
    assert(scala.util.Properties.javaClassPath.contains("munit"))

  test("worker classpath"):
    assert(sbt.internal.worker1.WorkerMain.foo() == 2)
end ATest
