package ch.cyberduck.core.s3;

/*
 * Copyright (c) 2002-2010 David Kocher. All rights reserved.
 *
 * http://cyberduck.ch/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to:
 * dkocher@cyberduck.ch
 */

import ch.cyberduck.core.AttributedList;
import ch.cyberduck.core.Credentials;
import ch.cyberduck.core.Host;
import ch.cyberduck.core.HostKeyCallback;
import ch.cyberduck.core.ListProgressListener;
import ch.cyberduck.core.ListService;
import ch.cyberduck.core.LoginCallback;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.PathAttributes;
import ch.cyberduck.core.PathContainerService;
import ch.cyberduck.core.Scheme;
import ch.cyberduck.core.UrlProvider;
import ch.cyberduck.core.auth.AWSSessionCredentialsRetriever;
import ch.cyberduck.core.cdn.Distribution;
import ch.cyberduck.core.cdn.DistributionConfiguration;
import ch.cyberduck.core.cloudfront.CloudFrontDistributionConfigurationPreloader;
import ch.cyberduck.core.cloudfront.WebsiteCloudFrontDistributionConfiguration;
import ch.cyberduck.core.date.RFC822DateFormatter;
import ch.cyberduck.core.exception.AccessDeniedException;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.exception.InteroperabilityException;
import ch.cyberduck.core.exception.LoginCanceledException;
import ch.cyberduck.core.features.*;
import ch.cyberduck.core.http.HttpSession;
import ch.cyberduck.core.kms.KMSEncryptionFeature;
import ch.cyberduck.core.oauth.OAuth2AuthorizationService;
import ch.cyberduck.core.oauth.OAuth2RequestInterceptor;
import ch.cyberduck.core.preferences.HostPreferences;
import ch.cyberduck.core.preferences.PreferencesReader;
import ch.cyberduck.core.proxy.Proxy;
import ch.cyberduck.core.proxy.ProxyFactory;
import ch.cyberduck.core.restore.Glacier;
import ch.cyberduck.core.shared.DefaultHomeFinderService;
import ch.cyberduck.core.shared.DefaultPathHomeFeature;
import ch.cyberduck.core.shared.DelegatingHomeFeature;
import ch.cyberduck.core.shared.DelegatingSchedulerFeature;
import ch.cyberduck.core.shared.DisabledBulkFeature;
import ch.cyberduck.core.ssl.DefaultX509KeyManager;
import ch.cyberduck.core.ssl.DisabledX509TrustManager;
import ch.cyberduck.core.ssl.ThreadLocalHostnameDelegatingTrustManager;
import ch.cyberduck.core.ssl.X509KeyManager;
import ch.cyberduck.core.ssl.X509TrustManager;
import ch.cyberduck.core.sts.STSAssumeRoleCredentialsRequestInterceptor;
import ch.cyberduck.core.threading.BackgroundExceptionCallable;
import ch.cyberduck.core.threading.CancelCallback;
import ch.cyberduck.core.transfer.TransferStatus;

import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jets3t.service.ServiceException;
import org.jets3t.service.impl.rest.XmlResponsesSaxParser;
import org.jets3t.service.impl.rest.httpclient.RegionEndpointCache;
import org.jets3t.service.model.StorageObject;
import org.jets3t.service.security.AWSCredentials;
import org.jets3t.service.security.AWSSessionCredentials;
import org.jets3t.service.security.ProviderCredentials;
import org.jets3t.service.utils.SignatureUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.amazonaws.services.s3.Headers.*;

public class S3Session extends HttpSession<RequestEntityRestStorageService> {
    private static final Logger log = LogManager.getLogger(S3Session.class);

    private final PreferencesReader preferences
            = new HostPreferences(host);

    private final S3AccessControlListFeature acl = new S3AccessControlListFeature(this);

    private final Versioning versioning = preferences.getBoolean("s3.versioning.enable")
            ? new S3VersioningFeature(this, acl) : null;

    private final RegionEndpointCache regions = new RegionEndpointCache();

    private final Glacier glacier = new Glacier(this, new S3LocationFeature(this, regions), trust, key);

    private final Encryption encryption = S3Session.isAwsHostname(host.getHostname())
            ? new KMSEncryptionFeature(this, new S3LocationFeature(this, regions), acl, trust, key) : null;

    private final WebsiteCloudFrontDistributionConfiguration cloudfront = new WebsiteCloudFrontDistributionConfiguration(this,
            new S3LocationFeature(this, regions), trust, key) {
        @Override
        public Distribution read(final Path container, final Distribution.Method method, final LoginCallback prompt) throws BackgroundException {
            final Distribution distribution = super.read(container, method, prompt);
            if(log.isDebugEnabled()) {
                log.debug(String.format("Cache distribution %s", distribution));
            }
            // Replace previously cached value
            final Set<Distribution> cached = distributions.getOrDefault(container, new HashSet<>());
            cached.add(distribution);
            distributions.put(container, cached);
            return distribution;
        }
    };

    private final Map<Path, Set<Distribution>> distributions = new ConcurrentHashMap<>();

    private S3CredentialsStrategy authentication;

    private final S3Protocol.AuthenticationHeaderSignatureVersion authenticationHeaderSignatureVersion
            = S3Protocol.AuthenticationHeaderSignatureVersion.getDefault(host.getProtocol());

    public S3Session(final Host host) {
        super(host, new S3BucketHostnameTrustManager(new DisabledX509TrustManager(), host.getHostname()), new DefaultX509KeyManager());
    }

    public S3Session(final Host host, final X509TrustManager trust, final X509KeyManager key) {
        super(host, new S3BucketHostnameTrustManager(trust, host.getHostname()), key);
    }

    @Override
    protected void logout() throws BackgroundException {
        try {
            client.shutdown();
        }
        catch(ServiceException e) {
            throw new S3ExceptionMappingService().map(e);
        }
    }

    protected XmlResponsesSaxParser getXmlResponseSaxParser() throws ServiceException {
        return new XmlResponsesSaxParser(client.getConfiguration(), false);
    }

    /**
     * @return the identifier for the signature algorithm.
     */
    protected String getSignatureIdentifier() {
        return "AWS";
    }

    public S3Protocol.AuthenticationHeaderSignatureVersion getSignatureVersion() {
        return authenticationHeaderSignatureVersion;
    }

    /**
     * @return header prefix for general Google Storage headers: x-goog-.
     */
    protected String getRestHeaderPrefix() {
        return "x-amz-";
    }

    /**
     * @return header prefix for Google Storage metadata headers: x-goog-meta-.
     */
    protected String getRestMetadataPrefix() {
        return "x-amz-meta-";
    }

    @Override
    protected RequestEntityRestStorageService connect(final Proxy proxy, final HostKeyCallback hostkey, final LoginCallback prompt, final CancelCallback cancel) throws BackgroundException {
        final HttpClientBuilder configuration = builder.build(proxy, this, prompt);
        authentication = this.configureCredentialsStrategy(configuration, prompt);
        if(preferences.getBoolean("s3.upload.expect-continue")) {
            final String header = HTTP.EXPECT_DIRECTIVE;
            if(log.isDebugEnabled()) {
                log.debug(String.format("Add request handler for %s", header));
            }
            configuration.addInterceptorLast(new HttpRequestInterceptor() {
                @Override
                public void process(final HttpRequest request, final HttpContext context) {
                    if(HttpPut.METHOD_NAME.equals(request.getRequestLine().getMethod())) {
                        // #7621
                        request.addHeader(header, HTTP.EXPECT_CONTINUE);
                    }
                }
            });
        }
        if(preferences.getBoolean("s3.bucket.requesterpays")) {
            // Only for AWS
            if(S3Session.isAwsHostname(host.getHostname())) {
                final String header = REQUESTER_PAYS_HEADER;
                if(log.isDebugEnabled()) {
                    log.debug(String.format("Add request handler for %s", header));
                }
                configuration.addInterceptorLast(new HttpRequestInterceptor() {
                    @Override
                    public void process(final HttpRequest request, final HttpContext context) {
                        // Downloading Objects in Requester Pays Buckets
                        if(HttpGet.METHOD_NAME.equals(request.getRequestLine().getMethod()) || HttpPost.METHOD_NAME.equals(request.getRequestLine().getMethod())) {
                            // For GET and POST requests, include x-amz-request-payer : requester in the header
                            request.addHeader(header, "requester");
                        }
                    }
                });
            }
        }
        configuration.addInterceptorLast(new HttpRequestInterceptor() {
            @Override
            public void process(final HttpRequest request, final HttpContext context) {
                // Ensure the required Host header is set prior to signing.
                final HttpHost host = (HttpHost) context.getAttribute(HttpCoreContext.HTTP_TARGET_HOST);
                if(host != null) {
                    if(host.getPort() == S3Session.this.host.getProtocol().getScheme().getPort()) {
                        request.setHeader(HttpHeaders.HOST, host.getHostName());
                    }
                    else {
                        request.setHeader(HttpHeaders.HOST, host.toHostString());
                    }
                }
            }
        });
        configuration.addInterceptorLast(new HttpRequestInterceptor() {
            private final RFC822DateFormatter formatter = new RFC822DateFormatter();

            @Override
            public void process(final HttpRequest request, final HttpContext context) {
                request.setHeader(S3_ALTERNATE_DATE, SignatureUtils.formatAwsFlavouredISO8601Date(client.getCurrentTimeWithOffset()));
            }
        });
        configuration.addInterceptorLast(new HttpRequestInterceptor() {
            @Override
            public void process(final HttpRequest request, final HttpContext context) {
                final ProviderCredentials credentials = client.getProviderCredentials();
                if(credentials instanceof AWSSessionCredentials) {
                    request.setHeader(SECURITY_TOKEN, ((AWSSessionCredentials) credentials).getSessionToken());
                }
            }
        });
        switch(authenticationHeaderSignatureVersion) {
            case AWS4HMACSHA256:
                configuration.addInterceptorLast(new S3AWS4SignatureRequestInterceptor(this));
                break;
            case AWS2:
                configuration.addInterceptorLast(new S3AWS2SignatureRequestInterceptor(this));
                break;
        }
        final RequestEntityRestStorageService client = new RequestEntityRestStorageService(this, configuration);
        client.setRegionEndpointCache(regions);
        return client;
    }

    protected S3CredentialsStrategy configureCredentialsStrategy(final HttpClientBuilder configuration,
                                                                 final LoginCallback prompt) throws LoginCanceledException {
        if(host.getProtocol().isOAuthConfigurable()) {
            final OAuth2RequestInterceptor oauth = new OAuth2RequestInterceptor(builder.build(ProxyFactory.get()
                    .find(host.getProtocol().getOAuthAuthorizationUrl()), this, prompt).build(), host, prompt)
                    .withRedirectUri(host.getProtocol().getOAuthRedirectUrl());
            if(host.getProtocol().getAuthorization() != null) {
                oauth.withFlowType(OAuth2AuthorizationService.FlowType.valueOf(host.getProtocol().getAuthorization()));
            }
            configuration.addInterceptorLast(oauth);
            final STSAssumeRoleCredentialsRequestInterceptor interceptor
                    = new STSAssumeRoleCredentialsRequestInterceptor(oauth, this, trust, key, prompt);
            configuration.addInterceptorLast(interceptor);
            configuration.setServiceUnavailableRetryStrategy(new S3AuthenticationResponseInterceptor(this, interceptor));
            return interceptor;
        }
        else {
            if(S3Session.isAwsHostname(host.getHostname())) {
                final S3AuthenticationResponseInterceptor interceptor;
                // Try auto-configure
                if(Scheme.isURL(host.getProtocol().getContext())) {
                    // Fetch temporary session token from instance metadata
                    interceptor = new S3AuthenticationResponseInterceptor(this,
                            new AWSSessionCredentialsRetriever(trust, key, host.getProtocol().getContext())
                    );
                }
                else {
                    // Fetch temporary session token from AWS CLI configuration
                    interceptor = new S3AuthenticationResponseInterceptor(this, new S3CredentialsStrategy() {
                        @Override
                        public Credentials get() throws LoginCanceledException {
                            return new S3CredentialsConfigurator(
                                    new ThreadLocalHostnameDelegatingTrustManager(trust, host.getHostname()), key, prompt).reload().configure(host);
                        }
                    });
                }
                configuration.setServiceUnavailableRetryStrategy(interceptor);
                return interceptor;
            }
            else {
                return host::getCredentials;
            }
        }
    }

    @Override
    public void login(final Proxy proxy, final LoginCallback prompt, final CancelCallback cancel) throws BackgroundException {
        final Credentials credentials = authentication.get();
        if(credentials.isAnonymousLogin()) {
            if(log.isDebugEnabled()) {
                log.debug(String.format("Connect with no credentials to %s", host));
            }
            client.setProviderCredentials(null);
        }
        else {
            if(credentials.getTokens().validate()) {
                if(log.isDebugEnabled()) {
                    log.debug(String.format("Connect with session credentials to %s", host));
                }
                client.setProviderCredentials(new AWSSessionCredentials(
                        credentials.getTokens().getAccessKeyId(), credentials.getTokens().getSecretAccessKey(),
                        credentials.getTokens().getSessionToken()));
            }
            else {
                if(log.isDebugEnabled()) {
                    log.debug(String.format("Connect with basic credentials to %s", host));
                }
                client.setProviderCredentials(new AWSCredentials(credentials.getUsername(), credentials.getPassword()));
            }
        }
        if(host.getCredentials().isPassed()) {
            log.warn(String.format("Skip verifying credentials with previous successful authentication event for %s", this));
            return;
        }
        try {
            final Path home = new DelegatingHomeFeature(new DefaultPathHomeFeature(host)).find();
            final Location.Name location = new S3PathStyleFallbackAdapter<>(client, new BackgroundExceptionCallable<Location.Name>() {
                @Override
                public Location.Name call() throws BackgroundException {
                    return new S3LocationFeature(S3Session.this, regions).getLocation(home);
                }
            }).call();
            if(log.isDebugEnabled()) {
                log.debug(String.format("Retrieved region %s", location));
            }
            if(!Location.unknown.equals(location)) {
                if(log.isDebugEnabled()) {
                    log.debug(String.format("Set default region to %s determined from %s", location, home));
                }
                //
                host.setProperty("s3.location", location.getIdentifier());
            }
        }
        catch(AccessDeniedException | InteroperabilityException e) {
            log.warn(String.format("Failure %s querying region", e));
            final Path home = new DefaultHomeFinderService(this).find();
            if(log.isDebugEnabled()) {
                log.debug(String.format("Retrieved %s", home));
            }
        }
    }

    public static boolean isAwsHostname(final String hostname) {
        return isAwsHostname(hostname, true);
    }

    public static boolean isAwsHostname(final String hostname, boolean cn) {
        if(cn) {
            // Matches s3.amazonaws.com
            // Matches s3.cn-north-1.amazonaws.com.cn
            // Matches s3.cn-northwest-1.amazonaws.com.cn
            // Matches s3-us-gov-west-1.amazonaws.com
            return hostname.matches("([a-z0-9\\-]+\\.)?s3(\\.dualstack)?(\\.[a-z0-9\\-]+)?(\\.vpce)?\\.amazonaws\\.com(\\.cn)?");
        }
        return hostname.matches("([a-z0-9\\-]+\\.)?s3(\\.dualstack)?(\\.[a-z0-9\\-]+)?(\\.vpce)?\\.amazonaws\\.com");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T _getFeature(final Class<T> type) {
        if(type == ListService.class) {
            final S3ListService proxy = new S3ListService(this, acl);
            return (T) new ListService() {
                @Override
                public AttributedList<Path> list(final Path directory, final ListProgressListener listener) throws BackgroundException {
                    return new S3PathStyleFallbackAdapter<>(client, new BackgroundExceptionCallable<AttributedList<Path>>() {
                        @Override
                        public AttributedList<Path> call() throws BackgroundException {
                            return proxy.list(directory, listener);
                        }
                    }).call();
                }
            };
        }
        if(type == Read.class) {
            return (T) new S3ReadFeature(this);
        }
        if(type == MultipartWrite.class) {
            if(S3Session.isAwsHostname(host.getHostname())) {
                return (T) new S3MultipartWriteFeature(this, acl);
            }
            return (T) new S3MultipartWriteFeature(this, acl);
        }
        if(type == Write.class) {
            return (T) new S3WriteFeature(this, acl);
        }
        if(type == Upload.class) {
            return (T) new S3ThresholdUploadService(this, acl);
        }
        if(type == Directory.class) {
            final S3DirectoryFeature proxy = new S3DirectoryFeature(this, new S3WriteFeature(this, acl), acl);
            return (T) new Directory<StorageObject>() {
                @Override
                public Path mkdir(final Path folder, final TransferStatus status) throws BackgroundException {
                    return new S3PathStyleFallbackAdapter<>(client, new BackgroundExceptionCallable<Path>() {
                        @Override
                        public Path call() throws BackgroundException {
                            return proxy.mkdir(folder, status);
                        }
                    }).call();
                }

                @Override
                public Directory<StorageObject> withWriter(final Write<StorageObject> writer) {
                    return proxy.withWriter(writer);
                }
            };
        }
        if(type == Move.class) {
            return (T) new S3MoveFeature(this, acl);
        }
        if(type == Copy.class) {
            if(S3Session.isAwsHostname(host.getHostname())) {
                return (T) new S3ThresholdCopyFeature(this);
            }
            return (T) new S3CopyFeature(this, acl);
        }
        if(type == Delete.class) {
            if(S3Session.isAwsHostname(host.getHostname())) {
                return (T) new S3ThresholdDeleteFeature(this, acl);
            }
            return (T) new S3DefaultDeleteFeature(this);
        }
        if(type == AclPermission.class) {
            return (T) acl;
        }
        if(type == Headers.class) {
            return (T) new S3MetadataFeature(this, acl);
        }
        if(type == Metadata.class) {
            return (T) new S3MetadataFeature(this, acl);
        }
        if(type == Touch.class) {
            return (T) new S3TouchFeature(this, acl);
        }
        if(type == Location.class) {
            return (T) new S3LocationFeature(this, regions);
        }
        if(type == Versioning.class) {
            return (T) versioning;
        }
        if(type == Logging.class) {
            return (T) new S3LoggingFeature(this);
        }
        if(type == Lifecycle.class) {
            return (T) new S3LifecycleConfiguration(this);
        }
        if(type == Encryption.class) {
            return (T) encryption;
        }
        if(type == Redundancy.class) {
            return (T) new S3StorageClassFeature(this, acl);
        }
        if(type == DistributionConfiguration.class) {
            return (T) cloudfront;
        }
        if(type == UrlProvider.class) {
            return (T) new S3UrlProvider(this, distributions);
        }
        if(type == Share.class) {
            return (T) new S3PublicUrlProvider(this, acl);
        }
        if(type == Find.class) {
            return (T) new S3FindFeature(this, acl);
        }
        if(type == AttributesFinder.class) {
            final S3AttributesFinderFeature proxy = new S3AttributesFinderFeature(this, acl);
            return (T) new AttributesFinder() {
                @Override
                public PathAttributes find(final Path file, final ListProgressListener listener) throws BackgroundException {
                    return new S3PathStyleFallbackAdapter<>(client, new BackgroundExceptionCallable<PathAttributes>() {
                        @Override
                        public PathAttributes call() throws BackgroundException {
                            return proxy.find(file, listener);
                        }
                    }).call();
                }
            };
        }
        if(type == TransferAcceleration.class) {
            // Only for AWS. Disable transfer acceleration for AWS GovCloud
            if(host.getHostname().endsWith(preferences.getProperty("s3.hostname.default"))) {
                return (T) new S3TransferAccelerationService(this);
            }
            return null;
        }
        if(type == Bulk.class) {
            if(preferences.getBoolean("s3.accelerate.enable")) {
                // Only for AWS. Disable transfer acceleration for AWS GovCloud
                if(host.getHostname().endsWith(preferences.getProperty("s3.hostname.default"))) {
                    return (T) new S3BulkTransferAccelerationFeature(this, new S3TransferAccelerationService(this));
                }
            }
            return (T) new DisabledBulkFeature();
        }
        if(type == Search.class) {
            return (T) new S3SearchFeature(this, acl);
        }
        if(type == Scheduler.class) {
            return (T) new DelegatingSchedulerFeature(new CloudFrontDistributionConfigurationPreloader(this));
        }
        if(type == Restore.class) {
            return (T) glacier;
        }
        if(type == Timestamp.class) {
            if(preferences.getBoolean("s3.timestamp.enable")) {
                return (T) new S3TimestampFeature(this);
            }
            return null;
        }
        if(type == PathContainerService.class) {
            return (T) S3PathContainerServiceFactory.get(host);
        }
        return super._getFeature(type);
    }
}
