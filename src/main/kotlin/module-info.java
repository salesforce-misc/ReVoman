module reVoman {
  requires kotlin.stdlib;
  requires org.graalvm.sdk;
  requires moshi;
  requires http4k.core;
  requires http4k.format.moshi;
  requires org.apache.commons.lang3;
  requires http4k.format.core;
  requires moshi.adapters;
  requires org.immutables.value.annotations;
  requires org.jetbrains.annotations;
  requires vador;
  requires vador.matchers;
  requires java.net.http;
  requires kotlin.faker;
  requires io.github.microutils.kotlinlogging;
  requires com.github.underscore;

  exports org.revcloud.revoman;
  exports org.revcloud.revoman.input;
  exports org.revcloud.revoman.output;
  exports org.revcloud.revoman.adapters;
  exports org.revcloud.revoman.response.types.salesforce;
}
