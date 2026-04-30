package example

import org.mockito.Mockito
import org.scalatest.funspec.AnyFunSpec

trait A {
  def b: Int
}

class Test1 extends AnyFunSpec {
  it("test 1") {
    val a = Mockito.mock(classOf[A])
    Mockito.when(a.b).thenReturn(3)
    assert(a.b == 3)
  }
}
