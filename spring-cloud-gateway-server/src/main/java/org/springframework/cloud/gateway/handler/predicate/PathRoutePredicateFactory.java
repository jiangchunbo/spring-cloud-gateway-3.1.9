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

package org.springframework.cloud.gateway.handler.predicate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.style.ToStringCreator;
import org.springframework.http.server.PathContainer;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPattern.PathMatchInfo;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_MATCHED_PATH_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_MATCHED_PATH_ROUTE_ID_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_PATH_CONTAINER_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_ROUTE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.putUriTemplateVariables;
import static org.springframework.http.server.PathContainer.parsePath;

/**
 * @author Spencer Gibb
 * @author Dhawal Kapil
 */
public class PathRoutePredicateFactory extends AbstractRoutePredicateFactory<PathRoutePredicateFactory.Config> {

	private static final Log log = LogFactory.getLog(PathRoutePredicateFactory.class);

	private static final String MATCH_TRAILING_SLASH = "matchTrailingSlash";

	private PathPatternParser pathPatternParser = new PathPatternParser();

	public PathRoutePredicateFactory() {
		super(Config.class);
	}

	private static void traceMatch(String prefix, Object desired, Object actual, boolean match) {
		if (log.isTraceEnabled()) {
			String message = String.format("%s \"%s\" %s against value \"%s\"", prefix, desired,
					match ? "matches" : "does not match", actual);
			log.trace(message);
		}
	}

	public void setPathPatternParser(PathPatternParser pathPatternParser) {
		this.pathPatternParser = pathPatternParser;
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList("patterns", MATCH_TRAILING_SLASH);
	}

	@Override
	public ShortcutType shortcutType() {
		return ShortcutType.GATHER_LIST_TAIL_FLAG;
	}

	@Override
	public Predicate<ServerWebExchange> apply(Config config) {
		// 1. 用于收集所有的 pattern
		final ArrayList<PathPattern> pathPatterns = new ArrayList<>();

		// 这里有一个警告：正在使用一个非 final 字段作为锁
		synchronized (this.pathPatternParser) {
			// 设置匹配可能存在的末尾 /
			pathPatternParser.setMatchOptionalTrailingSeparator(config.isMatchTrailingSlash());
			config.getPatterns().forEach(pattern -> {
				PathPattern pathPattern = this.pathPatternParser.parse(pattern);
				pathPatterns.add(pathPattern);
			});
		}
		return new GatewayPredicate() {
			@Override
			public boolean test(ServerWebExchange exchange) {
				PathContainer path = (PathContainer) exchange.getAttributes().computeIfAbsent(
						GATEWAY_PREDICATE_PATH_CONTAINER_ATTR,
						s -> parsePath(exchange.getRequest().getURI().getRawPath()));

				// 遍历每一个 path pattern，判断是否匹配 path，只取第一个命中的 path pattern
				PathPattern match = null;
				for (int i = 0; i < pathPatterns.size(); i++) {
					PathPattern pathPattern = pathPatterns.get(i);
					if (pathPattern.matches(path)) {
						match = pathPattern;
						break;
					}
				}

				// 匹配命中
				if (match != null) {
					traceMatch("Pattern", match.getPatternString(), path, true);
					PathMatchInfo pathMatchInfo = match.matchAndExtract(path);
					// 将匹配的 uri 变量放进去
					// 比如你这么配 Path=/user/{userId} 那么 {userId} 就是一个变量
					putUriTemplateVariables(exchange, pathMatchInfo.getUriVariables());
					exchange.getAttributes().put(GATEWAY_PREDICATE_MATCHED_PATH_ATTR, match.getPatternString());
					String routeId = (String) exchange.getAttributes().get(GATEWAY_PREDICATE_ROUTE_ATTR);
					if (routeId != null) {
						// populated in RoutePredicateHandlerMapping
						exchange.getAttributes().put(GATEWAY_PREDICATE_MATCHED_PATH_ROUTE_ID_ATTR, routeId);
					}
					return true;
				}
				else {
					traceMatch("Pattern", config.getPatterns(), path, false);
					return false;
				}
			}

			@Override
			public Object getConfig() {
				return config;
			}

			@Override
			public String toString() {
				return String.format("Paths: %s, match trailing slash: %b", config.getPatterns(),
						config.isMatchTrailingSlash());
			}
		};
	}

	@Validated
	public static class Config {

		private List<String> patterns = new ArrayList<>();

		/**
		 * 是否匹配末尾的 /。默认是 true
		 */
		private boolean matchTrailingSlash = true;

		public List<String> getPatterns() {
			return patterns;
		}

		public Config setPatterns(List<String> patterns) {
			this.patterns = patterns;
			return this;
		}

		/**
		 * @deprecated use {@link #isMatchTrailingSlash()}
		 */
		@Deprecated
		public boolean isMatchOptionalTrailingSeparator() {
			return isMatchTrailingSlash();
		}

		/**
		 * @deprecated use {@link #setMatchTrailingSlash(boolean)}
		 */
		@Deprecated
		public Config setMatchOptionalTrailingSeparator(boolean matchOptionalTrailingSeparator) {
			setMatchTrailingSlash(matchOptionalTrailingSeparator);
			return this;
		}

		public boolean isMatchTrailingSlash() {
			return matchTrailingSlash;
		}

		public Config setMatchTrailingSlash(boolean matchTrailingSlash) {
			this.matchTrailingSlash = matchTrailingSlash;
			return this;
		}

		@Override
		public String toString() {
			return new ToStringCreator(this).append("patterns", patterns)
					.append(MATCH_TRAILING_SLASH, matchTrailingSlash).toString();
		}

	}

}
