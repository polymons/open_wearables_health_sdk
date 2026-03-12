/// Controls which log messages the SDK emits.
///
/// - [none]:   No logs at all (neither console nor callbacks).
/// - [always]: Logs are always emitted regardless of build mode.
/// - [debug]:  Logs are emitted only in debug builds (the default).
enum OWLogLevel {
  none,
  always,
  debug;

  String get id {
    switch (this) {
      case OWLogLevel.none:
        return 'none';
      case OWLogLevel.always:
        return 'always';
      case OWLogLevel.debug:
        return 'debug';
    }
  }
}
