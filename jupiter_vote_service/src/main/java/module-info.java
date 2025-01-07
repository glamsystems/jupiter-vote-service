module systems.glam.jupiter_vote_service {
  requires java.net.http;

  requires systems.comodal.json_iterator;

  requires software.sava.core;
  requires software.sava.rpc;
  requires software.sava.solana_web2;
  requires software.sava.solana_programs;
  requires software.sava.anchor_programs;
  requires software.sava.core_services;
  requires software.sava.solana_services;
  requires software.sava.kms_core;

  uses software.sava.kms.core.signing.SigningServiceFactory;
}
