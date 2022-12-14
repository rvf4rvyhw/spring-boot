/*
 * Copyright 2012-2022 the original author or authors.
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

package org.springframework.boot.buildpack.platform.docker.transport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import com.sun.jna.Platform;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.Registry;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.TimeValue;

import org.springframework.boot.buildpack.platform.docker.configuration.ResolvedDockerHost;
import org.springframework.boot.buildpack.platform.socket.DomainSocket;
import org.springframework.boot.buildpack.platform.socket.NamedPipeSocket;

/**
 * {@link HttpClientTransport} that talks to local Docker.
 *
 * @author Phillip Webb
 * @author Scott Frederick
 */
final class LocalHttpClientTransport extends HttpClientTransport {

	private static final HttpHost LOCAL_DOCKER_HOST;

	static {
		try {
			LOCAL_DOCKER_HOST = HttpHost.create("docker://localhost");
		}
		catch (URISyntaxException ex) {
			throw new RuntimeException("Error creating local Docker host address", ex);
		}
	}

	private LocalHttpClientTransport(HttpClient client) {
		super(client, LOCAL_DOCKER_HOST);
	}

	static LocalHttpClientTransport create(ResolvedDockerHost dockerHost) {
		HttpClientBuilder builder = HttpClients.custom();
		builder.setConnectionManager(new LocalConnectionManager(dockerHost.getAddress()));
		builder.setSchemePortResolver(new LocalSchemePortResolver());
		return new LocalHttpClientTransport(builder.build());
	}

	/**
	 * {@link HttpClientConnectionManager} for local Docker.
	 */
	private static class LocalConnectionManager extends BasicHttpClientConnectionManager {

		LocalConnectionManager(String host) {
			super(getRegistry(host), null, null, new LocalDnsResolver());
		}

		private static Registry<ConnectionSocketFactory> getRegistry(String host) {
			RegistryBuilder<ConnectionSocketFactory> builder = RegistryBuilder.create();
			builder.register("docker", new LocalConnectionSocketFactory(host));
			return builder.build();
		}

	}

	/**
	 * {@link DnsResolver} that ensures only the loopback address is used.
	 */
	private static class LocalDnsResolver implements DnsResolver {

		private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

		@Override
		public InetAddress[] resolve(String host) throws UnknownHostException {
			return new InetAddress[] { LOOPBACK };
		}

		@Override
		public String resolveCanonicalHostname(String host) throws UnknownHostException {
			return LOOPBACK.getCanonicalHostName();
		}

	}

	/**
	 * {@link ConnectionSocketFactory} that connects to the local Docker domain socket or
	 * named pipe.
	 */
	private static class LocalConnectionSocketFactory implements ConnectionSocketFactory {

		private final String host;

		LocalConnectionSocketFactory(String host) {
			this.host = host;
		}

		@Override
		public Socket createSocket(HttpContext context) throws IOException {
			if (Platform.isWindows()) {
				return NamedPipeSocket.get(this.host);
			}
			return DomainSocket.get(this.host);
		}

		@Override
		public Socket connectSocket(TimeValue connectTimeout, Socket socket, HttpHost host,
				InetSocketAddress remoteAddress, InetSocketAddress localAddress, HttpContext context)
				throws IOException {
			return socket;
		}

	}

	/**
	 * {@link SchemePortResolver} for local Docker.
	 */
	private static class LocalSchemePortResolver implements SchemePortResolver {

		private static final int DEFAULT_DOCKER_PORT = 2376;

		@Override
		public int resolve(HttpHost host) {
			Args.notNull(host, "HTTP host");
			String name = host.getSchemeName();
			if ("docker".equals(name)) {
				return DEFAULT_DOCKER_PORT;
			}
			return -1;
		}

	}

}
