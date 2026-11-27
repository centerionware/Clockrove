import 'package:clockrove/maps/zillow_link.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('builds a Zillow homes URL from site coordinates', () {
    expect(
      zillowUriForCoordinates(40.7484, -73.9857),
      Uri.parse('https://www.zillow.com/homes/40.748400,-73.985700_rb/'),
    );
  });

  test('rejects non-finite or out-of-range coordinates', () {
    expect(() => zillowUriForCoordinates(double.nan, 0), throwsArgumentError);
    expect(
      () => zillowUriForCoordinates(0, double.infinity),
      throwsArgumentError,
    );
    expect(() => zillowUriForCoordinates(90.1, 0), throwsArgumentError);
    expect(() => zillowUriForCoordinates(0, -180.1), throwsArgumentError);
  });
}
