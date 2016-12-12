package com.twitter.finagle

import com.twitter.conversions.time._
import com.twitter.logging.{HasLogLevel, Level}
import com.twitter.util.{Await, Future}
import org.junit.runner.RunWith
import org.scalacheck.Gen
import org.scalatest.FunSuite
import org.scalatest.junit.{AssertionsForJUnit, JUnitRunner}
import org.scalatest.prop.GeneratorDrivenPropertyChecks

@RunWith(classOf[JUnitRunner])
class FailureTest extends FunSuite with AssertionsForJUnit with GeneratorDrivenPropertyChecks {
  private val exc = Gen.oneOf[Throwable](
    null,
    new Exception("first"),
    new Exception("second"))

  private val flag = Gen.oneOf(
    0L,
    Failure.Restartable,
    Failure.Interrupted,
    Failure.Wrapped,
    Failure.Rejected,
    Failure.Naming)
    // Failure.NonRetryable - Conflicts with Restartable, so omitted here.

  private val flag2 = for (f1 <- flag; f2 <- flag if f1 != f2) yield f1|f2

  test("simple failures with a cause") {
    val why = "boom!"
    val exc = new Exception(why)
    val failure = Failure(exc)
    val Failure(Some(`exc`)) = failure
    assert(failure.why == why)
  }

  test("equality") {
    forAll(flag2) { f =>
      val e1, e2 = new Exception

      assert(Failure(e1, f) == Failure(e1, f))
      assert(Failure(e1, f) != Failure(e2, f))
      assert(Failure(e1, f).hashCode == Failure(e1, f).hashCode)
    }
  }

  test("flags") {
    val e = new Exception

    for (flags <- Seq(flag, flag2)) {
      forAll(flags.suchThat(_ != 0)) { f =>
        assert(Failure(e, f).isFlagged(f))
        assert(Failure(e).flagged(f) == Failure(e, f))
        assert(Failure(e, f) != Failure(e, f).unflagged(f))
        assert(Failure(e, f).isFlagged(f))
        assert(!Failure(e, 0).isFlagged(f))
      }
    }
  }

  test("Failure.adapt(Failure)") {
    val parent = Failure("sadface", Failure.Restartable)

    val f = Failure.adapt(parent, Failure.Interrupted)
    assert(f.flags == (Failure.Restartable|Failure.Interrupted))
    assert(f.getCause == parent)
    assert(f.getMessage == "sadface")
    assert(f != parent)
  }

  test("Failure.adapt(Throwable)") {
    val parent = new Exception("sadface")

    val f = Failure.adapt(parent, Failure.Interrupted)
    assert(f.flags == Failure.Interrupted)
    assert(f.isFlagged(Failure.Interrupted))
    assert(f.getCause == parent)
    assert(f.getMessage == "sadface")
    assert(f != parent)
  }

  test("Failure.show") {
    assert(Failure("ok", Failure.Rejected|Failure.Restartable|Failure.Interrupted).show == Failure("ok", Failure.Interrupted|Failure.Rejected))
    val inner = new Exception
    assert(Failure.wrap(inner).show == inner)
    assert(Failure.wrap(Failure.wrap(inner)).show == inner)
  }

  test("Invalid Failure") {
    intercept[IllegalArgumentException] {
      Failure(null: Throwable, Failure.Wrapped)
    }
  }

  test("Invalid flag combinations") {
    intercept[IllegalArgumentException] {
      Failure("eh", Failure.NonRetryable|Failure.Restartable)
    }
  }

  private class WithLogLevel(val logLevel: Level) extends Exception with HasLogLevel

  test("Failure.apply uses HasLogLevel's logLevel") {
    Seq(Level.CRITICAL, Level.TRACE, Level.ALL).foreach { level =>
      assert(level == Failure(new WithLogLevel(level)).logLevel)
    }
  }

  test("Failure.apply follows chain to HasLogLevel") {
    val nested = new Exception(new WithLogLevel(Level.DEBUG))
    assert(Level.DEBUG == Failure(nested).logLevel)
  }

  test("Failure.apply defaults logLevel to warning") {
    assert(Level.WARNING == Failure(new Exception()).logLevel)
  }

  test("Failure.rejected sets correct flags") {
    val flags = Failure.Restartable | Failure.Rejected
    assert(Failure.rejected(":(").isFlagged(flags))
    assert(Failure.rejected(":(", new Exception).isFlagged(flags))
    assert(Failure.rejected(new Exception).isFlagged(flags))
  }

  test("Failure.ProcessFailures") {
    val echo = Service.mk((exc: Throwable) => Future.exception(exc))
    val service = new Failure.ProcessFailures().andThen(echo)

    def assertFail(exc: Throwable, expect: Throwable) = {
      val exc1 = intercept[Throwable] { Await.result(service(exc), 5.seconds) }
      assert(exc1 == expect)
    }

    assertFail(Failure("ok", Failure.Restartable), Failure("ok"))
    assertFail(Failure("ok"), Failure("ok"))
    assertFail(Failure("ok", Failure.Interrupted), Failure("ok", Failure.Interrupted))
    assertFail(Failure("ok", Failure.Interrupted|Failure.Restartable), Failure("ok", Failure.Interrupted))
    assertFail(Failure("ok", Failure.Rejected|Failure.NonRetryable), Failure("ok", Failure.Rejected|Failure.NonRetryable))

    val inner = new Exception
    assertFail(Failure.wrap(inner), inner)
  }

  test("Failure.flagsOf") {
    val failures = Seq(
      Failure("abc", new Exception, Failure.Interrupted|Failure.Restartable|Failure.Naming|Failure.Rejected|Failure.Wrapped),
      Failure("abc", Failure.NonRetryable),
      Failure("abc"),
      new Exception
    )
    val categories = Seq(
      Set("interrupted", "restartable", "wrapped", "rejected", "naming"),
      Set("nonretryable"),
      Set(),
      Set()
    )
    for ((f, c) <- failures.zip(categories)) {
      assert(Failure.flagsOf(f) == c)
    }
  }
}
