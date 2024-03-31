package org.galaxio.gatling

import io.gatling.core.check.Check

package object jdbc {
  type JdbcCheck = Check[List[Map[String, Any]]]
}
