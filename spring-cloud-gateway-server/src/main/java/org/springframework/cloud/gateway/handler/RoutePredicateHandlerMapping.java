/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.handler;

import java.util.function.Function;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping.ManagementPortType.DIFFERENT;
import static org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping.ManagementPortType.DISABLED;
import static org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping.ManagementPortType.SAME;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_HANDLER_MAPPER_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_ROUTE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * {@link RoutePredicateHandlerMapping} 继承自 Spring WebFlux 的 {@link AbstractHandlerMapping}，
 * 它的主要职责是为每个 HTTP 请求选择合适的处理程序（handler）。
 * <p>
 * 在 Spring Cloud Gateway 中，它用于根据路由规则（Route）判断请求是否符合某个路由的条件（Predicate），
 * 然后将匹配的路由与配套的过滤器连（WebHandler）关联起来
 *
 * @author Spencer Gibb
 */
public class RoutePredicateHandlerMapping extends AbstractHandlerMapping {

	/**
	 * 类型非常具体的 {@link FilteringWebHandler} 处理器。如果找到了 Route，就会快速返回这个对象作为 handler。
	 */
	private final FilteringWebHandler webHandler;

	/**
	 * TODO 路由定位器。其实就是从我们定义好的 route 中获取一堆路由。
	 */
	private final RouteLocator routeLocator;

	/**
	 * 来自于环境属性 management.server.port
	 */
	private final Integer managementPort;

	private final ManagementPortType managementPortType;

	public RoutePredicateHandlerMapping(FilteringWebHandler webHandler, RouteLocator routeLocator,
										GlobalCorsProperties globalCorsProperties, Environment environment) {
		this.webHandler = webHandler;
		this.routeLocator = routeLocator;

		this.managementPort = getPortProperty(environment, "management.server.");
		this.managementPortType = getManagementPortType(environment);
		setOrder(environment.getProperty(GatewayProperties.PREFIX + ".handler-mapping.order", Integer.class, 1));
		setCorsConfigurations(globalCorsProperties.getCorsConfigurations());
	}

	private ManagementPortType getManagementPortType(Environment environment) {
		// 获取属性 server.port
		Integer serverPort = getPortProperty(environment, "server.");

		// 如果 management 端口小于 0，那么就是禁用（而且是显式设置为小于 0）
		if (this.managementPort != null && this.managementPort < 0) {
			return DISABLED;
		}

		// 1. management port 未设置端口，底层应该默认与 server port 相同
		// 2. management port 设置为 8080，server port 没设置，那也认为相同
		// 3. management port 非 0，显式设置与 server port 相同
		return (this.managementPort == null
				|| (serverPort == null && this.managementPort.equals(8080))
				|| (this.managementPort != 0 && this.managementPort.equals(serverPort))) ? SAME : DIFFERENT;
	}

	private static Integer getPortProperty(Environment environment, String prefix) {
		return environment.getProperty(prefix + "port", Integer.class);
	}

	@Override
	protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
		// don't handle requests on management port if set and different than server port
		// 如果 management port 设置了，但是与 server port 不同，那么就不要处理
		if (this.managementPortType == DIFFERENT && this.managementPort != null
				&& exchange.getRequest().getLocalAddress() != null
				&& exchange.getRequest().getLocalAddress().getPort() == this.managementPort) {
			return Mono.empty();
		}
		exchange.getAttributes().put(GATEWAY_HANDLER_MAPPER_ATTR, getSimpleName());

		return lookupRoute(exchange) // 返回 Mono<Route>
				// .log("route-predicate-handler-mapping", Level.FINER) //name this
//				.map(r -> {
//					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
//					if (logger.isDebugEnabled()) {
//						logger.debug("Mapping [" + getExchangeDesc(exchange) + "] to " + r);
//					}
//
//					exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);
//					return webHandler;
//				})
				// 这里虽然叫 flatMap，但是只是为了异步寻找 WebHandler。
				// 不过我个人写了一个上面的 map 写法，感觉没太大差异，这又不是什么耗时阻塞的操作
				.flatMap((Function<Route, Mono<?>>) r -> {
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
					if (logger.isDebugEnabled()) {
						logger.debug("Mapping [" + getExchangeDesc(exchange) + "] to " + r);
					}

					// 🌚🌚🌚 注意这里，找到 router 之后，放到了 attributes 中，便于让后续的处理链处理
					exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);
					return Mono.just(webHandler);
				})
				// 走到这里也就是 lookupRoute 没找到
				.switchIfEmpty(Mono.empty().then(Mono.fromRunnable(() -> {
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
					if (logger.isTraceEnabled()) {
						logger.trace("No RouteDefinition found for [" + getExchangeDesc(exchange) + "]");
					}
				})));
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler, ServerWebExchange exchange) {
		// TODO: support cors configuration via properties on a route see gh-229
		// see RequestMappingHandlerMapping.initCorsConfiguration()
		// also see
		// https://github.com/spring-projects/spring-framework/blob/master/spring-web/src/test/java/org/springframework/web/cors/reactive/CorsWebFilterTests.java
		return super.getCorsConfiguration(handler, exchange);
	}

	// TODO: get desc from factory?
	private String getExchangeDesc(ServerWebExchange exchange) {
		StringBuilder out = new StringBuilder();
		out.append("Exchange: ");
		out.append(exchange.getRequest().getMethod());
		out.append(" ");
		out.append(exchange.getRequest().getURI());
		return out.toString();
	}

	protected Mono<Route> lookupRoute(ServerWebExchange exchange) {
		return this.routeLocator.getRoutes()
				// individually filter routes so that filterWhen error delaying is not a
				// problem
				.concatMap(route -> Mono.just(route).filterWhen(r -> {
							// add the current route we are testing
							exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, r.getId());
							return r.getPredicate().apply(exchange);
						})
						// instead of immediately stopping main flux due to error, log and
						// swallow it
						.doOnError(e -> logger.error("Error applying predicate for route: " + route.getId(), e))
						.onErrorResume(e -> Mono.empty()))
				// .defaultIfEmpty() put a static Route not found
				// or .switchIfEmpty()
				// .switchIfEmpty(Mono.<Route>empty().log("noroute"))
				.next()
				// TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("Route matched: " + route.getId());
					}
					validateRoute(route, exchange);
					return route;
				});

		/*
		 * TODO: trace logging if (logger.isTraceEnabled()) {
		 * logger.trace("RouteDefinition did not match: " + routeDefinition.getId()); }
		 */
	}

	/**
	 * Validate the given handler against the current request.
	 * <p>
	 * The default implementation is empty. Can be overridden in subclasses, for example
	 * to enforce specific preconditions expressed in URL mappings.
	 *
	 * @param route    the Route object to validate
	 * @param exchange current exchange
	 * @throws Exception if validation failed
	 */
	@SuppressWarnings("UnusedParameters")
	protected void validateRoute(Route route, ServerWebExchange exchange) {
	}

	protected String getSimpleName() {
		return "RoutePredicateHandlerMapping";
	}

	/**
	 * management 端口的类型
	 */
	public enum ManagementPortType {

		/**
		 * The management port has been disabled.
		 * 禁用 management 端口
		 */
		DISABLED,

		/**
		 * The management port is the same as the server port.
		 * 与 server 端口相同
		 */
		SAME,

		/**
		 * The management port and server port are different.
		 * 与 server 端口不同
		 */
		DIFFERENT;

	}

}
