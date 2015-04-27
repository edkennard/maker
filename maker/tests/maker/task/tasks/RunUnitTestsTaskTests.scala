package maker.task.tasks

import maker.utils.FileUtils._
import maker.utils.os.Command
import org.scalatest.{Matchers, FunSuite}
import maker.project.TestModule

class RunUnitTestsTaskTests extends FunSuite with Matchers {//with ParallelTestExecution{

  test("Broken tests fail"){
    withTestDir{
      dir => 
        TestModule.createMakerProjectFile(dir)
        val module = new TestModule(dir, "RunUnitTestsTaskTestsModule")
        module.appendDefinitionToProjectFile(dir)
        module.writeTest(
          "foo/Test.scala",
          """
            package foo

            import org.scalatest.FunSuite

            class Test extends FunSuite{
              test("This should fail"){
                assert(1 === 2)
              }
            }
          """
        )
        module.testOutputFile.exists should be (false)
        val makerClasspath = s"""${file("maker", "target-maker", "classes").getAbsolutePath}:${file("maker", "target-maker", "test-classes").getAbsolutePath}"""
        val makerScript = file("maker.py").getAbsolutePath
        val command = Command(
          "python",
          makerScript,
          "-E",
          "RunUnitTestsTaskTestsModule.test",
          "-z",
          "-l",
          file("logback-unit-tests.xml").getAbsolutePath,
          "-L",
          "40"
        ).
        withWorkingDirectory(dir).
        withExitValues(0, 1)

        val result = command.run
        result should equal (1)
        module.testOutputFile.exists should be (true)
    }
  }

  ignore("Test reports picks up failure"){
    withTempDir{
      dir => 
        val module = new TestModule(dir, "RunUnitTestsTaskTests")
        module.writeTest(
          "foo/Test.scala",
          """
            package foo

            import org.scalatest.FunSuite

            class Test extends FunSuite{
              test("This should fail"){
                assert(1 === 2)
              }
            }
          """
        )
        module.test

        assert(module.testOutputFile.exists, "Test output should exist")
    }
  }


  ignore("Unit test runs"){
    withTempDir{
      root => 
        val proj = new TestModule(root, "RunUnitTestsTaskTests")

        proj.writeSrc(
          "foo/Foo.scala", 
          """
          package foo
          case class Foo(x : Double){
            val fred = 10
            def double() = x + x
          }
          """
        )
        proj.writeTest(
          "foo/FooTest.scala",
          """
          package foo
          import org.scalatest.FunSuite
          class FooTest extends FunSuite{
            test("test foo"){
              val foo1 = Foo(1.0)
              val foo2 = Foo(1.0)
              assert(foo1 === foo2)
            }
          }
          """
        )
        assert(proj.test.succeeded)
    }
  }

  ignore("Failing test fails again"){
    withTempDir{
      root => 
        val proj = new TestModule(root, "RunUnitTestsTaskTests")
        proj.writeSrc(
          "foo/Foo.scala", 
          """
          package foo
          case class Foo(x : Double){
            val fred = 10
            def double() = x + x
          }
          """
        )
        val testFile = proj.writeTest(
          "foo/FooTest.scala",
          """
          package foo
          import org.scalatest.FunSuite
          import java.io._
          class FooTest extends FunSuite{
            val f = new File(".")
            test("test foo"){
              assert(1 === 2)
            }
          }
          """
        )
        assert(proj.testCompile.succeeded, "Expected compilation to succeed")

        assert(proj.test.failed, "Expected test to fail")
    }
  }

  ignore("Can re-run failing tests"){
    withTempDir{
      root => 
        val proj = new TestModule(root, "RunUnitTestsTaskTests")

        proj.writeTest(
          "foo/GoodTest.scala",
          """
          package foo
          import org.scalatest.FunSuite
          class GoodTest extends FunSuite{
            test("test foo"){
              assert(1 === 1)
            }
          }
          """
        )
        proj.writeTest( 
          "foo/BadTest.scala",
          """
          package foo
          import org.scalatest.FunSuite
          class BadTest extends FunSuite{
            test("test foo"){
              assert(1 === 2)
            }
          }
          """
        )

        proj.test
        assert(proj.testResults.failedTests.size === 1, "Expecting exactly one failure")
        assert(proj.testResults.passedTests.size === 1, "Expecting exactly one pass")

        //This time we should only run the failed test
        //so there should be no passing tests
        proj.testFailedSuites
        assert(proj.testResults.failedTests.size === 1)
        assert(proj.testResults.passedTests.size === 0)

        //Repair the broken test, check there is one passing test
        proj.writeTest( 
          "foo/BadTest.scala",
          """
          package foo
          import org.scalatest.FunSuite
          class BadTest extends FunSuite{
            test("test foo"){
              assert(1 === 1)
            }
          }
          """
        )
        proj.testFailedSuites
        assert(proj.testResults.failedTests.size === 0)
        assert(proj.testResults.passedTests.size === 1)

        //Re-run failed tests - should do nothing
        proj.testFailedSuites
        assert(proj.testResults.failedTests.size === 0)
        assert(proj.testResults.passedTests.size === 1)


        //Run all tests - should have two passes
        proj.test
        assert(proj.testResults.failedTests.size === 0)
        assert(proj.testResults.passedTests.size === 2)

    }
  }

}
