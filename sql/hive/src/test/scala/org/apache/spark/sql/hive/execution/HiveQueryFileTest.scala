/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive.execution

import java.io.File

import org.apache.spark.sql.catalyst.util._

/**
 * A framework for running the query tests that are listed as a set of text files.
 * 用于运行列为文本文件集的查询测试的框架
 * TestSuites that derive from this class must provide a map of testCaseName -> testCaseFiles
 * that should be included. Additionally, there is support for whitelisting and blacklisting
 * tests as development progresses.
 */
abstract class HiveQueryFileTest extends HiveComparisonTest {
  /**
    * A list of tests deemed out of scope and thus completely disregarded
    * 被列为范围的测试列表，因此被完全忽视
    * */
  def blackList: Seq[String] = Nil

  /**
   * The set of tests that are believed to be working in catalyst. Tests not in whiteList
   * blacklist are implicitly marked as ignored.
    *被认为在催化剂中工作的一组测试,不在whiteList黑名单中的测试被隐式标记为忽略
   */
  def whiteList: Seq[String] = ".*" :: Nil

  def testCases: Seq[(String, File)]

  val runAll =
    !(System.getProperty("spark.hive.alltests") == null) ||
    runOnlyDirectories.nonEmpty ||
    skipDirectories.nonEmpty

  val whiteListProperty = "spark.hive.whitelist"
  // Allow the whiteList to be overridden by a system property
  //允许系统属性覆盖白名单
  val realWhiteList =
    Option(System.getProperty(whiteListProperty)).map(_.split(",").toSeq).getOrElse(whiteList)

  // Go through all the test cases and add them to scala test.
  //通过所有的测试用例,并添加到scala测试
  testCases.sorted.foreach {
    case (testCaseName, testCaseFile) =>
      if (blackList.map(_.r.pattern.matcher(testCaseName).matches()).reduceLeft(_||_)) {
        logDebug(s"Blacklisted test skipped $testCaseName")
      } else if (realWhiteList.map(_.r.pattern.matcher(testCaseName).matches()).reduceLeft(_||_) ||
        runAll) {
        // Build a test case and submit it to scala test framework...
        //构建一个测试用例并将其提交到Scala测试框架…
        val queriesString = fileToString(testCaseFile)
        createQueryTest(testCaseName, queriesString)
      } else {
        // Only output warnings for the built in whitelist as this clutters the output when the user
        // trying to execute a single test from the commandline.
        //只能输出内置白名单的警告,因为这个用户将输出的输出尝试从命令行执行单个测试。
        if (System.getProperty(whiteListProperty) == null && !runAll) {
          ignore(testCaseName) {}
        }
      }
  }
}
