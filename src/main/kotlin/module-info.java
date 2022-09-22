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
  requires kotlin.logging.jvm;
  requires kotlin.faker;

  exports org.revcloud;
  exports org.revcloud.input;
  exports org.revcloud.output;
  exports org.revcloud.adapters;
  exports org.revcloud.response.types.salesforce;
}
