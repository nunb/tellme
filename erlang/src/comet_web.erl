%% @author Mochi Media <dev@mochimedia.com>
%% @copyright 2010 Mochi Media <dev@mochimedia.com>

%% @doc Web server for comet.

-module(comet_web).
-author("Mochi Media <dev@mochimedia.com>").

-export([start/1, stop/0, loop/2]).

-ifdef(TEST).
-define(COMET_TIMEOUT, 200).
-else.
-define(COMET_TIMEOUT, 10000).
-endif.

%% External API

start(Options) ->
    {DocRoot, Options1} = get_option(docroot, Options),
    Loop = fun(Req) -> ?MODULE:loop(Req, DocRoot) end,

    mochiweb_http:start([{name, ?MODULE}, {loop, Loop} | Options1]).

stop() ->
    mochiweb_http:stop(?MODULE).

loop(Req, DocRoot) ->
	"/" ++ Path = Req:get(path),
	try
		case Req:get(method) of
			'GET' ->
				Req:serve_file(Path, DocRoot);
			'POST' ->
				case Path of
					"channel" ->
						Command = jiffy:decode(Req:recv_body()),
						case Command of
							{[{<<"command">>, <<"get-uuid">>}]} ->
								{ok, Uuid, Sid} = comet_auth:new(),
								Req:ok({"text/plain", jiffy:encode({[{uuid, Uuid}, {sid, Sid}]})});

							{[{<<"command">>, <<"auth">>}, {<<"sid">>, Sid}, {<<"uuid">>, Uuid}, {<<"osid">>, OSid}]} ->
								case comet_auth:auth(Uuid, Sid, OSid) of
									ok -> Req:ok({"text/plain", jiffy:encode({[{ack, ok}]})});
									{error, EReason} -> Req:ok({"text/plain", jiffy:encode({[{ack, error}, {reason, EReason}]})})
								end;

							{[{<<"command">>, <<"message">>}, {<<"sid">>, Sid}, {<<"uuid">>, Uuid}, {<<"message">>, Message}]} ->
								comet_auth:send_message(Uuid, Sid, Message),
								Req:ok({"text/plain", jiffy:encode({[{ack, ok}]})});

							_ -> Req:ok({"text/plain", jiffy:encode({[{ack, error}, {reason, invalid}]})})
						end;

					"backchannel" ->
						Command = jiffy:decode(Req:recv_body()),
						case Command of

							{[{<<"uuid">>, Uuid}, {<<"sid">>, Sid}]} ->
								comet_auth:subscribe(self(), Uuid, Sid),
								case wait_for_data(Req) of
									{error, socket_closed} ->
										comet_auth:release(Uuid, Sid);
									{error, timeout} ->
										comet_auth:unsubscribe(Uuid, Sid),
										Req:ok({"text/plain", jiffy:encode({[{command, reconnect}]})});
									{ok, Message} ->
										Req:ok({"text/plain", jiffy:encode({[{command, message}, {message, Message}]})})
								end;

							%	receive
							%		Message ->
							%			Req:ok({"text/plain", jiffy:encode({[{command, message}, {message, Message}]})})
							%	after ?COMET_TIMEOUT ->
							%			comet_auth:unsubscribe(Uuid, Sid),
							%			Req:ok({"text/plain", jiffy:encode({[{command, reconnect}]})})
							%	end;
							_ -> Req:ok({"text/plain", jiffy:encode({[{ack, error}, {reason, invalid}]})})
						end;
					% path catch all
					_ ->
						Req:not_found()
				end;
			% method catch all
			_ ->
				Req:respond({501, [], []})
		end
	catch
		throw:{fail, Reason} ->
			Req:ok({"text/plain", jsonp_wrap("fail", "{ack: \"error\", reason: \"" ++ to_string(Reason) ++ "\"}")});
		Type:What ->
			Report = ["web request failed",
					{path, Path},
					{type, Type}, {what, What},
					{trace, erlang:get_stacktrace()}],
			error_logger:error_report(Report),
			Req:ok({"text/plain", "{ack: \"error\", reason: \"" ++ to_string(Report) ++ "\"}"})
	end.

%% Internal API

% as per https://groups.google.com/forum/?fromgroups#!topic/mochiweb/O5K3RsYiyXw
wait_for_data(Req) ->
	Socket = Req:get(socket),
	inet:setopts(Socket, [{active, once}]),

	receive
		{tcp_closed, Socket} -> {error, socket_closed};
		Message -> {ok, Message}
	after
		?COMET_TIMEOUT -> {error, timeout}
	end.

hex_uuid() -> os:cmd("uuidgen").

get_option(Option, Options) ->
    {proplists:get_value(Option, Options), proplists:delete(Option, Options)}.

jsonp_wrap(Callback, Json) ->
	case Callback of
		undefined -> Json;
		_ -> Callback ++ "('" ++ Json ++ "');"
	end.

string_format(Pattern, Values) ->
    lists:flatten(io_lib:format(Pattern, Values)).
    
to_string(Value) ->
	string_format("~p", [Value]).

to_integer(undefined) ->
	undefined;
	
to_integer(String) ->
	case string:to_integer(String) of
		{error, _} -> throw({fail, not_an_integer});
		{X, _} -> X
	end.

value_to_binary(Value) when is_list(Value) ->
	list_to_binary(Value);

value_to_binary(Value) ->
	Value.

value_or(Value, Alternative) when Value == undefined ->
	Alternative;

value_or(Value, _Alternative) ->
	Value.
	
%%
%% Tests
%%
-ifdef(TEST).
-include_lib("eunit/include/eunit.hrl").

catch_fail(Fun) ->
	try
		Fun(),
		?assertEqual("didn't fail", "")
	catch
		_Type:_What ->
			ok
	end.

get_body(Url, Command) ->
	Response = ibrowse:send_req("http://localhost:8080/" ++ Url, [], post, jiffy:encode({Command})),
	% error_logger:info_msg("Body: ~p~n", [Response]),
	case Response of
		{ok, _, _, Body} ->
			jiffy:decode(Body);
		_ -> ?assertEqual(true, false)
	end.

get_uuid_test() ->
	ibrowse:start(),
	Response = get_body("channel", [{command, <<"get-uuid">>}]),
	?assertMatch({[{<<"uuid">>, _}, {<<"sid">>, _}]}, Response),
	ibrowse:stop().

auth_test() ->
	ibrowse:start(),
	{[{<<"uuid">>, Uuid1}, {<<"sid">>, Sid1}]} = get_body("channel", [{command, <<"get-uuid">>}]),
	{[{<<"uuid">>, Uuid2}, {<<"sid">>, Sid2}]} = get_body("channel", [{command, <<"get-uuid">>}]),

	{[{<<"ack">>, <<"error">>}, {<<"reason">>, _}]} = get_body("channel", [{command, <<"auth">>}, {sid, Sid1}, {uuid, Uuid1}, {osid, Sid2}]),
	{[{<<"ack">>, <<"ok">>}]} = get_body("channel", [{command, <<"auth">>}, {sid, Sid2}, {uuid, Uuid2}, {osid, Sid1}]),

	ibrowse:stop().

backchannel_test() ->
	ibrowse:start(),
	{[{<<"uuid">>, Uuid1}, {<<"sid">>, Sid1}]} = get_body("channel", [{command, <<"get-uuid">>}]),
	{[{<<"uuid">>, Uuid2}, {<<"sid">>, Sid2}]} = get_body("channel", [{command, <<"get-uuid">>}]),

	{[{<<"ack">>, <<"error">>}, {<<"reason">>, _}]} = get_body("channel", [{command, <<"auth">>}, {sid, Sid1}, {uuid, Uuid1}, {osid, Sid2}]),
	{[{<<"ack">>, <<"ok">>}]} = get_body("channel", [{command, <<"auth">>}, {sid, Sid2}, {uuid, Uuid2}, {osid, Sid1}]),

	{[{<<"ack">>, <<"ok">>}]} = get_body("channel", [{command, <<"message">>}, {sid, Sid2}, {uuid, Uuid2}, {message, "A message"}]),
	{[{<<"command">>, <<"message">>}, {<<"message">>, "A message"}]} = get_body("backchannel", [{uuid, Uuid1}, {sid, Sid1}]),

	ibrowse:stop().

%backchannel_timeout_test() ->
%	ibrowse:start(),
%	{[{<<"uuid">>, Uuid1}, {<<"sid">>, Sid1}]} = get_body("channel", [{command, <<"get-uuid">>}]),
%	{[{<<"uuid">>, Uuid2}, {<<"sid">>, Sid2}]} = get_body("channel", [{command, <<"get-uuid">>}]),
%
%	{[{<<"ack">>, <<"error">>}, {<<"reason">>, _}]} = get_body("channel", [{command, <<"auth">>}, {sid, Sid1}, {uuid, Uuid1}, {osid, Sid2}]),
%	{[{<<"ack">>, <<"ok">>}]} = get_body("channel", [{command, <<"auth">>}, {sid, Sid2}, {uuid, Uuid2}, {osid, Sid1}]),
%
%	timer:sleep(200),
%	{[{<<"command">>, <<"reconnect">>}]} = get_body("backchannel", [{uuid, Uuid1}, {sid, Sid1}]),
%
%	ibrowse:stop().

-endif.