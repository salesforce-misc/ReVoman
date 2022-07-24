package org.revcloud.input

import org.immutables.value.Value
import org.immutables.value.Value.Style.ImplementationVisibility

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Value.Style(
  typeImmutable = "*",
  typeAbstract = ["*Def"],
  builder = "configure",
  build = "off",
  depluralize = true,
  add = "",
  visibility = ImplementationVisibility.PUBLIC)
internal annotation class Config
