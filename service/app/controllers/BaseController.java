package controllers;

import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.service.SunbirdMWService;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.response.ResponseParams;
import org.sunbird.common.models.response.ResponseParams.StatusType;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LoggerEnum;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.request.ExecutionContext;
import org.sunbird.common.responsecode.ResponseCode;

import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.pattern.Patterns;
import akka.util.Timeout;
import play.mvc.Result;
import play.libs.Json;
import play.libs.F.Function;
import play.libs.F.Promise;
import play.mvc.Controller;
import play.mvc.Results;
import play.mvc.Http.Request;

/**
 * This controller we can use for writing some common method.
 * 
 * @author Mahesh Kumar Gangula
 */
public class BaseController extends Controller {

	private static final int AKKA_WAIT_TIME = 10;
	protected Timeout timeout = new Timeout(AKKA_WAIT_TIME, TimeUnit.SECONDS);

	public Promise<Result> actorResponseHandler(org.sunbird.common.request.Request request, Timeout timeout,
			String responseKey, Request httpReq) {
		Object actorRef = SunbirdMWService.getRequestRouter();

		Function<Object, Result> function = new Function<Object, Result>() {
			public Result apply(Object result) {
				if (result instanceof Response) {
					Response response = (Response) result;
					return createCommonResponse(request().path(), responseKey, response);
				} else if (result instanceof ProjectCommonException) {
					return createCommonExceptionResult(request().path(), (ProjectCommonException) result);
				} else {
					ProjectLogger.log("Unsupported Actor Response format", LoggerEnum.INFO.name());
					return createCommonExceptionResult(request().path(), new Exception());
				}
			}
		};

		if (actorRef instanceof ActorRef) {
			return Promise.wrap(Patterns.ask((ActorRef) actorRef, request, timeout)).map(function);
		} else {
			return Promise.wrap(Patterns.ask((ActorSelection) actorRef, request, timeout)).map(function);
		}
	}

	public static Result createCommonExceptionResult(String path, Exception exception) {
		Response reponse = createResponseOnException(path, exception);
		int status = ResponseCode.SERVER_ERROR.getResponseCode();
		if (exception instanceof ProjectCommonException) {
			ProjectCommonException me = (ProjectCommonException) exception;
			status = me.getResponseCode();
		}
		return Results.status(status, Json.toJson(reponse));
	}

	public static Response createResponseOnException(String path, Exception exception) {
		Response response = getErrorResponse(exception);
		response.setVer("");
		if (path != null) {
			response.setVer(getApiVersion(path));
		}
		response.setId(getApiResponseId());
		response.setTs(ProjectUtil.getFormattedDate());
		return response;
	}

	protected static Response getErrorResponse(Exception e) {
		Response response = new Response();
		ResponseParams resStatus = new ResponseParams();
		String message = setMessage(e);
		resStatus.setErrmsg(message);
		resStatus.setStatus(StatusType.FAILED.name());
		if (e instanceof ProjectCommonException) {
			ProjectCommonException me = (ProjectCommonException) e;
			resStatus.setErr(me.getCode());
			response.setResponseCode(ResponseCode.getHeaderResponseCode(me.getResponseCode()));
		} else {
			resStatus.setErr(ResponseCode.SERVER_ERROR.name());
			response.setResponseCode(ResponseCode.SERVER_ERROR);
		}
		response.setParams(resStatus);
		return response;
	}

	protected static String setMessage(Exception e) {
		if (e instanceof ProjectCommonException) {
			return e.getMessage();
		} else if (e instanceof akka.pattern.AskTimeoutException) {
			return "Request processing taking too long time. Please try again later.";
		} else {
			return "Something went wrong in server while processing the request";
		}
	}

	public static ResponseParams createResponseParamObj(ResponseCode code) {
		ResponseParams params = new ResponseParams();
		if (code.getResponseCode() != 200) {
			params.setErr(code.getErrorCode());
			params.setErrmsg(code.getErrorMessage());
		}
		params.setMsgid(ExecutionContext.getRequestId());
		params.setStatus(ResponseCode.getHeaderResponseCode(code.getResponseCode()).name());
		return params;
	}

	public Result createCommonResponse(String path, Response response) {
		return Results.ok(Json.toJson(BaseController.createSuccessResponse(path, response)));
	}

	public Result createCommonResponse(String path, String key, Response response) {
		if (!StringUtils.isBlank(key)) {
			Object value = response.getResult().get(JsonKey.RESPONSE);
			response.getResult().remove(JsonKey.RESPONSE);
			response.getResult().put(key, value);
		}
		return Results.ok(Json.toJson(BaseController.createSuccessResponse(path, response)));
	}

	public static Response createSuccessResponse(String path, Response response) {

		if (StringUtils.isNotBlank(path)) {
			response.setVer(getApiVersion(path));
		} else {
			response.setVer("");
		}
		response.setId(getApiResponseId());
		response.setTs(ProjectUtil.getFormattedDate());
		ResponseCode code = ResponseCode.getResponse(ResponseCode.success.getErrorCode());
		code.setResponseCode(ResponseCode.OK.getResponseCode());
		response.setParams(createResponseParamObj(code));
		return response;
	}

	public static String getApiVersion(String path) {

		return path.split("[/]")[1];
	}

	private static String getApiResponseId() {
		return "api.telemetry";
	}

}
