create function merlin.subtract_later_ranges(curr_date tstzmultirange, later_dates tstzmultirange[])
returns tstzmultirange
immutable
language plpgsql as $$
  declare
	  ret tstzmultirange := curr_date;
	  later_date tstzmultirange;
begin
	foreach later_date in array later_dates loop
		ret := ret - later_date;
	end loop;
	return ret;
end
$$;

comment on function merlin.subtract_later_ranges(curr_date tstzmultirange, later_dates tstzmultirange[]) is e''
  'Used by the derived_events view that produces from the singular interval of time that a source covers a set of disjoint intervals.\n'
  'The disjointedness arises from where future sources'' spans are subtracted from this one.\n'
  'For example, if a source is valid at t=0, and covers span s=1 to s=5, and there is a source valid at t=1 with a span s=2 to s=3\n'
  'and another valid at t=2 with a span 3 to 4, then this source should have those spans subtracted and should only be valid over [1,2] and [4,5].';
