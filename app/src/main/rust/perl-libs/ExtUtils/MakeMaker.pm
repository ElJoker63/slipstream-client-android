package ExtUtils::MakeMaker;

use strict;
use File::Spec;

package MM;

sub maybe_command {
    my ($class, $file) = @_;
    $file = $class if @_ == 1;
    return unless defined $file && -f $file;
    return $file if -x $file;
    return $file if $file =~ /\.(?:exe|cmd|bat|com)\z/i;
    return;
}

1;
