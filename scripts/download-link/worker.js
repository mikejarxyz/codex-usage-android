export default {
    fetch(request, env) {
        const url = new URL(request.url);
        if (url.pathname !== "/codex-usage/download.apk") {
            return new Response("Not found", { status: 404 });
        }
        if (request.method !== "GET" && request.method !== "HEAD") {
            return new Response("Method not allowed", {
                status: 405,
                headers: { Allow: "GET, HEAD" },
            });
        }
        return new Response(null, {
            status: 302,
            headers: {
                Location: env.LATEST_APK_URL,
                "Cache-Control": "no-store",
            },
        });
    },
};
