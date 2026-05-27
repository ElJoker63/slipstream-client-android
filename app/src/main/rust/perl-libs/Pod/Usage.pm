package Pod::Usage;

use strict;
use Exporter ();

our @ISA = qw(Exporter);
our @EXPORT_OK = qw(pod2usage);

sub pod2usage {
    my %args = @_ == 1 ? (message => $_[0]) : @_;
    print STDERR $args{message}, "\n" if defined $args{message};
    exit($args{exitval} // 1);
}

1;
