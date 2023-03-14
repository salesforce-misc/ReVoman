module org.revcloud.revoman {
  requires transitive kotlin.stdlib;
  requires transitive moshi;
  requires transitive moshi.adapters;
  requires transitive vador;
  requires transitive vador.matchers;
  requires org.graalvm.sdk;
  requires http4k.core;
  requires http4k.format.moshi;
  requires org.apache.commons.lang3;
  requires http4k.format.core;
  requires org.immutables.value.annotations;
  requires org.jetbrains.annotations;
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
