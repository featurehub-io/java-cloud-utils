package cd.connect.jersey.prometheus;

import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;

/**
 * @author Richard Vowles
 */
@Provider
public class PrometheusDynamicFeature implements DynamicFeature {
  private final boolean profileAll;
	private final boolean profileNone;
  private final String prefix;

  public PrometheusDynamicFeature() {
    this(profileAll(), profileNone());
  }

	private static String configured(String name) {
		String val = System.getenv(name);
		if (val == null) {
			val = System.getenv(name.toUpperCase().replace(".", "_"));
			if (val == null) {
				val = System.getProperty(name);
			}
		}

		return val;
	}

  private static boolean profileAll() {
		String val = configured("prometheus.jersey.all");

	  return val == null || Boolean.parseBoolean(val);
  }

	private static boolean profileNone() {
		String val = configured("prometheus.jersey.none");

		return val == null || Boolean.parseBoolean(val);
	}

  public PrometheusDynamicFeature(boolean profileAll, boolean profileNone) {
    this.prefix = configured("prometheus.jersey.prefix");

    GlobalJerseyMetrics.init(prefix);

    this.profileAll = profileAll;
		this.profileNone = profileNone;
  }

  @Override
  public void configure(ResourceInfo resourceInfo, FeatureContext context) {
    Prometheus annotation = resourceInfo.getResourceMethod().getAnnotation(Prometheus.class);
    if (annotation != null || profileAll) {
      context.register(new PrometheusFilter(resourceInfo, prefix, annotation, profileNone));
    }
  }
}
