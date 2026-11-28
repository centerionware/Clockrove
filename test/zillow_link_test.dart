import 'package:clockrove/maps/zillow_link.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('builds a Zillow property search URL from a house-level address', () {
    expect(
      zillowUriForAddress('2040 Broadway St APT 303, San Francisco, CA 94115'),
      Uri.parse(
        'https://www.zillow.com/homes/2040-Broadway-St-APT-303,-San-Francisco,-CA-94115_rb/',
      ),
    );
  });

  test('normalizes surrounding and repeated whitespace', () {
    expect(
      zillowUriForAddress('  123 Main St,  Fort Collins, CO 80521  '),
      Uri.parse(
        'https://www.zillow.com/homes/123-Main-St,-Fort-Collins,-CO-80521_rb/',
      ),
    );
  });

  test('rejects an empty address', () {
    expect(() => zillowUriForAddress('   '), throwsArgumentError);
  });

  test('prefers the property address and falls back to the customer', () {
    expect(
      zillowSearchAddress(
        propertyAddress: ' 123 Main St, Fort Collins, CO 80521 ',
        customerAddress: '500 Customer Rd, Loveland, CO 80537',
      ),
      '123 Main St, Fort Collins, CO 80521',
    );
    expect(
      zillowSearchAddress(
        propertyAddress: '  ',
        customerAddress: '500 Customer Rd, Loveland, CO 80537',
      ),
      '500 Customer Rd, Loveland, CO 80537',
    );
    expect(zillowSearchAddress(), isNull);
  });
}
