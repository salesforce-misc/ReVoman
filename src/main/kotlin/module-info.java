open module revoman {
  requires kotlin.stdlib;
  requires vador;
  requires vador.matchers;
  requires org.graalvm.sdk;
  requires http4k.core;
  requires http4k.format.moshi;
  requires http4k.format.core;
  requires org.apache.commons.lang3;
  requires org.immutables.value.annotations;
  requires org.jetbrains.annotations;
  requires kotlin.faker;
  requires http4k.client.apache;
  requires com.squareup.moshi;
  requires moshi.adapters;
  requires underscore;
  requires com.google.common;
  requires io.github.oshai.kotlinlogging;

  exports org.revcloud.revoman;
  exports org.revcloud.revoman.input;
  exports org.revcloud.revoman.output;
}
