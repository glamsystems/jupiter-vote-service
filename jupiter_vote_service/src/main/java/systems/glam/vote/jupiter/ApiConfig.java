package systems.glam.vote.jupiter;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record ApiConfig(String basePath, int port) {

  public static ApiConfig createDefault() {
    return new ApiConfig("/api/v0/", 7073);
  }

  public static ApiConfig parseConfig(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return createDefault();
    } else {
      final var parser = new ApiConfig.Parser();
      ji.testObject(parser);
      return parser.create();
    }
  }

  private static final class Parser implements FieldBufferPredicate {

    private String basePath;
    private int port = 7073;

    private ApiConfig create() {
      return new ApiConfig(requireNonNullElse(basePath, "/api/v0/"), port);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("basePath", buf, offset, len)) {
        basePath = ji.readString();
      } else if (fieldEquals("port", buf, offset, len)) {
        port = ji.readInt();
      } else {
        throw new IllegalStateException("Unknown ApiConfig field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
