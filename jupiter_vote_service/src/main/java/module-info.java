module systems.glam.jupiter_vote_service {
  requires java.net.http;
  requires jdk.httpserver;

  requires systems.comodal.json_iterator;

  requires software.sava.core;
  requires software.sava.rpc;
  requires software.sava.solana_web2;
  requires software.sava.solana_programs;
  requires software.sava.anchor_programs;
  requires software.sava.ravina_core;
  requires software.sava.ravina_solana;
  requires software.sava.kms_core;

  requires systems.glam.ix_proxy;

  uses software.sava.kms.core.signing.SigningServiceFactory;
}
