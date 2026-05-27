package Locale::Maketext::Simple;

use strict;

sub import {
    my ($class, %args) = @_;
    my $export = $args{Export} || 'loc';

    no strict 'refs';
    *{caller() . "::$export"} = sub {
        my $text = shift;
        $text =~ s/%([1-9]\d*)/defined $_[$1 - 1] ? $_[$1 - 1] : "%$1"/eg;
        return $text;
    } if $export;
}

1;
